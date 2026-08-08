package com.fundlink.ai.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.ConfigWriter;
import com.fundlink.ai.agent.diagnosis.DiagnosisAgent;
import com.fundlink.ai.agent.diagnosis.DiagnosisResult;
import com.fundlink.ai.agent.requirement.RequirementAgent;
import com.fundlink.ai.agent.requirement.RequirementResult;
import com.fundlink.ai.agent.testgen.TestGenAgent;
import com.fundlink.ai.agent.testgen.TestGenResult;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.gateway.TokenUsage;
import com.fundlink.ai.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Agent Loop 编排器 — 状态机 + SSE 推送 + 重试控制 (设计 §3)
 * <p>
 * 状态转换: PENDING → ANALYZE → VALIDATE → DRYRUN → DECISION_POINT → PUBLISH → PUBLISHED
 */
@Slf4j
@Service
public class AgentLoopOrchestrator {

    private final RequirementAgent requirementAgent;
    private final ConfigWriter configWriter;
    private final TemplateValidator templateValidator;
    private final FlowDryRunner flowDryRunner;
    private final TestGenAgent testGenAgent;
    private final DiagnosisAgent diagnosisAgent;
    private final LoopTracer loopTracer;
    private final AiTaskMapper taskMapper;
    private final LoopEventPublisher eventPublisher;
    private final ObjectMapper json = new ObjectMapper();

    /** 等待人工决策的 CompletableFuture */
    private final Map<Long, CompletableFuture<DecisionRequest>> pendingDecisions = new ConcurrentHashMap<>();
    /** 运行中的 LoopState */
    private final Map<Long, LoopState> runningStates = new ConcurrentHashMap<>();

    public AgentLoopOrchestrator(RequirementAgent requirementAgent, ConfigWriter configWriter,
                                  TemplateValidator templateValidator, FlowDryRunner flowDryRunner,
                                  TestGenAgent testGenAgent, DiagnosisAgent diagnosisAgent,
                                  LoopTracer loopTracer, AiTaskMapper taskMapper,
                                  LoopEventPublisher eventPublisher) {
        this.requirementAgent = requirementAgent;
        this.configWriter = configWriter;
        this.templateValidator = templateValidator;
        this.flowDryRunner = flowDryRunner;
        this.testGenAgent = testGenAgent;
        this.diagnosisAgent = diagnosisAgent;
        this.loopTracer = loopTracer;
        this.taskMapper = taskMapper;
        this.eventPublisher = eventPublisher;
    }

    /** 启动闭环 — @Async 异步执行 */
    @Async
    public void start(Long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.error("[LOOP] Task not found: {}", taskId);
            return;
        }
        if (!"PENDING".equals(task.getStatus())) {
            log.warn("[LOOP] Task {} already started, status={}", taskId, task.getStatus());
            return;
        }

        LoopState s = new LoopState();
        s.taskId = taskId;
        s.taskNo = task.getTaskNo();
        s.documentText = task.getDocumentText();
        s.providerCode = task.getProviderCode();
        s.flowType = task.getFlowType() != null ? task.getFlowType() : "LOAN";
        s.round = task.getCurrentRound() != null ? task.getCurrentRound() : 0;
        s.maxRounds = task.getMaxRounds() != null ? task.getMaxRounds() : 3;
        s.phase = TaskPhase.ANALYZE;
        s.previousErrors = new ArrayList<>();

        runningStates.put(taskId, s);

        // Update task status
        task.setStatus("ANALYZE");
        taskMapper.updateById(task);

        eventPublisher.phaseStart(taskId, "ANALYZE", s.round + 1, s.maxRounds);

        runLoop(s);
    }

    /** 人工决策入口 */
    public void decide(Long taskId, DecisionRequest req) {
        CompletableFuture<DecisionRequest> future = pendingDecisions.get(taskId);
        if (future != null) {
            future.complete(req);
        } else {
            log.warn("[LOOP] No pending decision for task {}", taskId);
        }
    }

    // -- state machine --

    private void runLoop(LoopState s) {
        while (s.phase != TaskPhase.PUBLISHED && s.phase != TaskPhase.FAILED) {
            try {
                switch (s.phase) {
                    case ANALYZE -> doAnalyze(s);
                    case VALIDATE -> doValidate(s);
                    case DRYRUN -> doDryRun(s);
                    case DIAGNOSE -> doDiagnose(s);
                    case DECISION_POINT -> doDecision(s);
                    case PUBLISH -> doPublish(s);
                }
            } catch (Exception e) {
                log.error("[LOOP] Phase {} error  task={}: {}", s.phase, s.taskId, e.getMessage(), e);
                eventPublisher.phaseError(s.taskId, s.phase.name(), e.getMessage());

                // Transition to DIAGNOSE for recoverable errors
                s.lastError = e.getMessage();
                s.phase = TaskPhase.DIAGNOSE;
            }
        }

        // Cleanup
        runningStates.remove(s.taskId);
        pendingDecisions.remove(s.taskId);
    }

    private void doAnalyze(LoopState s) {
        log.info("[LOOP] ANALYZE  task={}  round={}/{}", s.taskId, s.round + 1, s.maxRounds);
        eventPublisher.phaseProgress(s.taskId, "ANALYZE", "正在解析接口文档...");

        long start = System.currentTimeMillis();
        RequirementResult result = requirementAgent.analyze(
                s.documentText, s.providerCode, s.previousErrors);
        long duration = System.currentTimeMillis() - start;

        if (result.getParseError() != null) {
            eventPublisher.phaseError(s.taskId, "ANALYZE", "LLM 解析失败: " + result.getParseError());
            s.lastError = "ANALYZE parse error: " + result.getParseError();
            s.phase = TaskPhase.DIAGNOSE;
            loopTracer.trace(s.taskId, s.taskNo + "-A-" + s.round, "ANALYZE", "requirement",
                    "document len=" + (s.documentText != null ? s.documentText.length() : 0),
                    result.getParseError(), null, duration, false, result.getParseError());
            return;
        }

        s.currentResult = result;

        // Write config to FundLink
        ConfigWriter.WriteResult write = configWriter.writeAll(result, s.providerCode, s.flowType);
        s.writeResult = write;
        if (!write.isSuccess()) {
            eventPublisher.phaseError(s.taskId, "ANALYZE", "配置写入失败: " + write.getError());
            s.lastError = "Config write failed: " + write.getError();
            s.phase = TaskPhase.DIAGNOSE;
            loopTracer.trace(s.taskId, s.taskNo + "-A-" + s.round, "ANALYZE", "requirement",
                    "mappings=" + (result.getFieldMappings() != null ? result.getFieldMappings().size() : 0),
                    write.getError(), null, duration, false, write.getError());
            return;
        }

        eventPublisher.phaseComplete(s.taskId, "ANALYZE",
                String.format("解析完成: %d 字段映射, %d 流程节点, Provider=%d, Template=%d",
                        result.getFieldMappings() != null ? result.getFieldMappings().size() : 0,
                        result.getFlowDsl() != null && result.getFlowDsl().getNodes() != null
                                ? result.getFlowDsl().getNodes().size() : 0,
                        write.getProviderId(), write.getTemplateId()));

        loopTracer.trace(s.taskId, s.taskNo + "-A-" + s.round, "ANALYZE", "requirement",
                "document len=" + (s.documentText != null ? s.documentText.length() : 0),
                "OK", null, duration, true, null);

        // Save to DB
        persistTask(s, "VALIDATE");

        s.phase = TaskPhase.VALIDATE;
        eventPublisher.phaseStart(s.taskId, "VALIDATE", s.round + 1, s.maxRounds);
    }

    private void doValidate(LoopState s) {
        log.info("[LOOP] VALIDATE  task={}", s.taskId);
        eventPublisher.phaseProgress(s.taskId, "VALIDATE", "生成测试数据并验证模板渲染...");

        RequirementResult rr = s.currentResult;
        if (rr == null || s.writeResult == null) {
            s.lastError = "Missing ANALYZE result";
            s.phase = TaskPhase.DIAGNOSE;
            return;
        }

        long start = System.currentTimeMillis();

        // 1. TestGen: generate previewData + testCases
        TestGenResult testGen = testGenAgent.generate(
                rr.getFlowDsl(), rr.getFieldMappings(), s.providerCode);
        s.testGen = testGen;

        if (testGen.getParseError() != null) {
            eventPublisher.phaseError(s.taskId, "VALIDATE", "TestGen 失败: " + testGen.getParseError());
            s.lastError = "TestGen error: " + testGen.getParseError();
            s.phase = TaskPhase.DIAGNOSE;
            return;
        }

        // 2. Validate template rendering
        Long templateId = s.writeResult.getTemplateId();
        Map<String, Object> previewData = testGen.getPreviewData();
        if (previewData == null || previewData.isEmpty()) {
            log.warn("[LOOP] No previewData from TestGen — using empty map");
            previewData = Map.of();
        }

        TemplateValidator.ValidationResult vr = templateValidator.validate(
                templateId, previewData, rr.getFieldMappings());
        long duration = System.currentTimeMillis() - start;

        if (!vr.isSuccess()) {
            eventPublisher.phaseError(s.taskId, "VALIDATE", "模板验证失败: " + vr.getErrorMsg());
            s.lastError = "VALIDATE: " + vr.getErrorMsg();
            s.validationError = vr;
            s.phase = TaskPhase.DIAGNOSE;
            loopTracer.trace(s.taskId, s.taskNo + "-V-" + s.round, "VALIDATE", "testgen",
                    "templateId=" + templateId, vr.getErrorMsg(), null, duration, false, vr.getErrorMsg());
            return;
        }

        eventPublisher.phaseComplete(s.taskId, "VALIDATE",
                "模板渲染验证通过, testCases=" + (testGen.getTestCases() != null ? testGen.getTestCases().size() : 0));

        loopTracer.trace(s.taskId, s.taskNo + "-V-" + s.round, "VALIDATE", "testgen",
                "templateId=" + templateId, "OK", null, duration, true, null);

        persistTask(s, "DRYRUN");

        s.phase = TaskPhase.DRYRUN;
        eventPublisher.phaseStart(s.taskId, "DRYRUN", s.round + 1, s.maxRounds);
    }

    private void doDryRun(LoopState s) {
        log.info("[LOOP] DRYRUN  task={}", s.taskId);
        eventPublisher.phaseProgress(s.taskId, "DRYRUN", "正在干跑测试流程...");

        RequirementResult rr = s.currentResult;
        TestGenResult tg = s.testGen;
        if (tg == null) {
            s.lastError = "Missing TestGen result — skipping DRYRUN";
            s.phase = TaskPhase.DECISION_POINT;
            s.decisionType = "PUBLISH_CONFIRM";
            return;
        }

        Long flowId = s.writeResult.getFlowId();
        if (flowId == null || flowId == 0) {
            s.lastError = "Missing flowId — skipping DRYRUN";
            s.phase = TaskPhase.DECISION_POINT;
            s.decisionType = "PUBLISH_CONFIRM";
            return;
        }

        // Ensure flow is published before dry-run (defensive — FundLink creates with status=1 already)
        try {
            URI pubUri = new URI("http://localhost:8080/api/admin/flows/" + flowId + "/publish");
            HttpURLConnection pubConn = (HttpURLConnection) pubUri.toURL().openConnection();
            pubConn.setRequestMethod("PUT");
            pubConn.setRequestProperty("Content-Type", "application/json");
            pubConn.setDoOutput(true);
            pubConn.setConnectTimeout(5000);
            pubConn.setReadTimeout(5000);
            pubConn.getOutputStream().write("{}".getBytes(StandardCharsets.UTF_8));
            int pubCode = pubConn.getResponseCode();
            log.info("[LOOP] Flow publish before DRYRUN  flowId={}  httpCode={}", flowId, pubCode);
        } catch (Exception e) {
            log.warn("[LOOP] Flow publish before DRYRUN failed (non-fatal): {}", e.getMessage());
        }

        long start = System.currentTimeMillis();
        FlowDryRunner.DryRunResult dr = flowDryRunner.dryRun(
                s.taskId, flowId, tg,
                rr.getFlowDsl() != null ? rr.getFlowDsl().getNodes() : Collections.emptyList(),
                rr.getFlowDsl() != null ? rr.getFlowDsl().getEdges() : Collections.emptyList());
        long duration = System.currentTimeMillis() - start;

        s.dryRun = dr;

        if (!dr.isSuccess()) {
            eventPublisher.phaseError(s.taskId, "DRYRUN", "干跑失败: " + dr.getErrorMsg());
            s.lastError = "DRYRUN: " + dr.getErrorMsg();
            s.phase = TaskPhase.DIAGNOSE;
            loopTracer.trace(s.taskId, s.taskNo + "-D-" + s.round, "DRYRUN", "dryrunner",
                    "flowId=" + flowId, dr.getErrorMsg(), null, duration, false, dr.getErrorMsg());
            return;
        }

        int branchCount = dr.getBranches() != null ? dr.getBranches().size() : 0;
        eventPublisher.phaseComplete(s.taskId, "DRYRUN", "干跑通过: " + branchCount + " 个分支全部成功");

        loopTracer.trace(s.taskId, s.taskNo + "-D-" + s.round, "DRYRUN", "dryrunner",
                "flowId=" + flowId + " branches=" + branchCount, "OK", null, duration, true, null);

        // All phases passed → ask publish confirm
        s.decisionType = "PUBLISH_CONFIRM";
        s.phase = TaskPhase.DECISION_POINT;
    }

    private void doDiagnose(LoopState s) {
        log.info("[LOOP] DIAGNOSE  task={}  round={}", s.taskId, s.round);

        Map<String, Object> context = new LinkedHashMap<>();
        try {
            context.put("round", s.round);
            context.put("providerCode", s.providerCode);
            context.put("flowType", s.flowType);
            if (s.currentResult != null && s.currentResult.getFieldMappings() != null) {
                context.put("fieldMappingCount", s.currentResult.getFieldMappings().size());
            }
        } catch (Exception ignored) {}

        long start = System.currentTimeMillis();
        DiagnosisResult diag = diagnosisAgent.diagnose(
                s.phase.name(), s.lastError != null ? s.lastError : "未知错误", context);
        long duration = System.currentTimeMillis() - start;

        s.lastDiagnosis = diag;

        loopTracer.trace(s.taskId, s.taskNo + "-DIAG-" + s.round, "DIAGNOSE", "diagnosis",
                "phase=" + s.phase + " error=" + (s.lastError != null ? s.lastError.substring(0,
                        Math.min(100, s.lastError.length())) : ""),
                diag.getRootCause(), null, duration, true, null);

        if (s.round < s.maxRounds - 1) {
            // Retry available
            s.decisionType = "RECOVERY";
        } else {
            // Max rounds exhausted
            s.decisionType = "RECOVERY_EXHAUSTED";
        }

        s.phase = TaskPhase.DECISION_POINT;
    }

    private void doDecision(LoopState s) {
        log.info("[LOOP] DECISION_POINT  task={}  type={}  round={}", s.taskId, s.decisionType, s.round);

        String summary;
        List<String> options;

        if ("PUBLISH_CONFIRM".equals(s.decisionType)) {
            summary = "所有验证通过！是否发布流程？";
            options = List.of("PUBLISH", "ABORT");
        } else {
            DiagnosisResult d = s.lastDiagnosis;
            summary = d != null && d.getRootCause() != null
                    ? d.getRootCause() + " → " + (d.getFixSuggestion() != null ? d.getFixSuggestion() : "")
                    : "验证失败: " + (s.lastError != null ? s.lastError : "未知错误");
            if (s.round >= s.maxRounds - 1) {
                options = List.of("SKIP", "EDIT_AND_RETRY", "ABORT");
            } else {
                options = List.of("RETRY", "SKIP", "EDIT_AND_RETRY", "ABORT");
            }
        }

        persistTask(s, "DECISION_POINT");
        eventPublisher.decisionRequired(s.taskId, s.decisionType, summary, options);

        // Wait for human decision
        CompletableFuture<DecisionRequest> future = new CompletableFuture<>();
        pendingDecisions.put(s.taskId, future);

        DecisionRequest decision;
        try {
            decision = future.get(10, TimeUnit.MINUTES); // 10 min timeout
        } catch (Exception e) {
            log.warn("[LOOP] Decision timeout for task {}", s.taskId);
            decision = new DecisionRequest();
            decision.setTaskId(s.taskId);
            decision.setDecision("ABORT");
        }

        log.info("[LOOP] Decision received  task={}  decision={}", s.taskId, decision.getDecision());

        switch (decision.getDecision()) {
            case "RETRY" -> {
                s.round++;
                s.previousErrors.add(s.lastDiagnosis != null ? s.lastDiagnosis
                        : createErrorDiag(s.lastError));
                s.phase = TaskPhase.ANALYZE;
                persistTask(s, "ANALYZE");
                eventPublisher.phaseStart(s.taskId, "ANALYZE", s.round + 1, s.maxRounds);
            }
            case "SKIP" -> {
                // Continue to publish if coming from VALIDATE skip
                s.decisionType = "PUBLISH_CONFIRM";
                // Re-enter decision with publish confirm
                doDecision(s);
            }
            case "EDIT_AND_RETRY" -> {
                s.round++;
                // 如果前端传了编辑后的结果，直接用（跳过 LLM 重新解析）
                if (decision.getEditedResult() != null) {
                    log.info("[LOOP] EDIT_AND_RETRY with editedResult  task={}", s.taskId);
                    s.currentResult = decision.getEditedResult();
                    ConfigWriter.WriteResult write = configWriter.writeAll(
                            decision.getEditedResult(), s.providerCode, s.flowType);
                    s.writeResult = write;
                    if (!write.isSuccess()) {
                        log.warn("[LOOP] EDIT_AND_RETRY config rewrite failed: {}", write.getError());
                        s.lastError = "Edited config write failed: " + write.getError();
                        s.phase = TaskPhase.DIAGNOSE;
                    } else {
                        s.phase = TaskPhase.VALIDATE;
                        persistTask(s, "VALIDATE");
                        eventPublisher.phaseStart(s.taskId, "VALIDATE", s.round + 1, s.maxRounds);
                    }
                } else {
                    // 无编辑内容 — 纯重试
                    s.previousErrors.add(s.lastDiagnosis != null ? s.lastDiagnosis
                            : createErrorDiag(s.lastError));
                    s.phase = TaskPhase.ANALYZE;
                    persistTask(s, "ANALYZE");
                    eventPublisher.phaseStart(s.taskId, "ANALYZE", s.round + 1, s.maxRounds);
                }
            }
            case "PUBLISH" -> {
                s.phase = TaskPhase.PUBLISH;
            }
            case "ABORT" -> {
                taskFailed(s, "人工终止");
            }
        }
    }

    private void doPublish(LoopState s) {
        log.info("[LOOP] PUBLISH  task={}  flowId={}", s.taskId,
                s.writeResult != null ? s.writeResult.getFlowId() : "null");

        if (s.writeResult != null && s.writeResult.getFlowId() != null) {
            try {
                // PUT /api/admin/flows/{id}/publish — idempotent (flows already status=1)
                URI uri = new URI("http://localhost:8080/api/admin/flows/"
                        + s.writeResult.getFlowId() + "/publish");
                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setRequestMethod("PUT");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.getOutputStream().write("{}".getBytes());
                conn.getResponseCode();
            } catch (Exception e) {
                log.warn("[LOOP] Publish API call failed (non-fatal): {}", e.getMessage());
            }
        }

        // Write back successful diagnosis to RAG
        if (s.lastDiagnosis != null && s.lastDiagnosis.getConfidence() >= 0.7) {
            AiTask task = taskMapper.selectById(s.taskId);
            if (task != null) {
                loopTracer.writebackKnowledge(task, s.lastDiagnosis);
            }
        }

        s.phase = TaskPhase.PUBLISHED;
        persistTask(s, "PUBLISHED");
        eventPublisher.taskComplete(s.taskId, "PUBLISHED",
                String.format("流程发布完成, 共 %d 轮", s.round + 1));
    }

    private void taskFailed(LoopState s, String reason) {
        s.phase = TaskPhase.FAILED;
        persistTask(s, "FAILED");
        eventPublisher.taskFailed(s.taskId, reason, s.round + 1);
    }

    private void persistTask(LoopState s, String status) {
        try {
            AiTask task = taskMapper.selectById(s.taskId);
            if (task != null) {
                task.setStatus(status);
                task.setCurrentRound(s.round);
                if (s.currentResult != null) {
                    try {
                        task.setCurrentResult(json.writeValueAsString(s.currentResult));
                    } catch (Exception ignored) {}
                }
                task.setUpdateTime(LocalDateTime.now());
                taskMapper.updateById(task);
            }
        } catch (Exception e) {
            log.error("[LOOP] Failed to persist task {}: {}", s.taskId, e.getMessage());
        }
    }

    private DiagnosisResult createErrorDiag(String error) {
        DiagnosisResult d = new DiagnosisResult();
        d.setPhase("ANALYZE");
        d.setRootCause(error != null ? error : "Unknown error");
        d.setFixSuggestion("请人工检查并修正配置");
        d.setConfidence(0.5);
        return d;
    }

    // -- inner classes (import from loop package) --

    /** Loop 运行时状态 */
    static class LoopState {
        Long taskId;
        String taskNo;
        String documentText;
        String providerCode;
        String flowType;
        int round;
        int maxRounds;
        TaskPhase phase;
        String decisionType;
        String lastError;
        RequirementResult currentResult;
        ConfigWriter.WriteResult writeResult;
        TestGenResult testGen;
        TemplateValidator.ValidationResult validationError;
        FlowDryRunner.DryRunResult dryRun;
        DiagnosisResult lastDiagnosis;
        List<DiagnosisResult> previousErrors;
    }
}
