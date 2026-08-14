package com.fundlink.ai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.FlowTypeDetector;
import com.fundlink.ai.agent.loop.AgentLoopOrchestrator;
import com.fundlink.ai.agent.loop.DecisionRequest;
import com.fundlink.ai.agent.loop.MultiLoopOrchestrator;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent Loop REST API — 轮询模式闭环控制 (SSE 已移除，2026-08)
 * <p>
 * 前端流程：POST /api/ai/loop 创建即启动 → 轮询 GET /{taskId} 拿状态与决策上下文
 * → POST /{taskId}/decide 提交决策 → 继续轮询。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/loop")
public class LoopController {

    private final AgentLoopOrchestrator orchestrator;
    private final MultiLoopOrchestrator multiOrchestrator;
    private final AiTaskMapper taskMapper;
    private final ObjectMapper json = new ObjectMapper();

    public LoopController(AgentLoopOrchestrator orchestrator,
                          MultiLoopOrchestrator multiOrchestrator,
                          AiTaskMapper taskMapper) {
        this.orchestrator = orchestrator;
        this.multiOrchestrator = multiOrchestrator;
        this.taskMapper = taskMapper;
    }

    /** Create loop task and start async execution — 创建即启动（启动点从 /stream 迁移过来） */
    @PostMapping
    public CopilotController.ApiAiResponse<Map<String, Object>> create(@RequestBody CreateLoopRequest req) {
        AiTask task = new AiTask();
        task.setTaskNo("LOOP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        task.setTaskType("LOOP");
        task.setStatus("PENDING");
        task.setFlowType(req.getFlowType() != null && !req.getFlowType().isBlank()
                ? req.getFlowType().toUpperCase()
                : FlowTypeDetector.detect(req.getDocumentText(), null));
        task.setProviderCode(req.getProviderCode());
        task.setDocumentText(req.getDocumentText());
        task.setCurrentRound(0);
        task.setMaxRounds(req.getMaxRounds() != null ? req.getMaxRounds() : 3);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);

        log.info("[LOOP] Task created  id={}  taskNo={}  provider={}",
                task.getId(), task.getTaskNo(), task.getProviderCode());

        // 创建即启动（@Async，HTTP 响应不等待）
        orchestrator.start(task.getId());

        return CopilotController.ApiAiResponse.success(Map.of(
                "taskId", task.getId(),
                "taskNo", task.getTaskNo()
        ));
    }

    /** Create multi-loop task: split doc → create parent + N sub-tasks → start each */
    @PostMapping("/multi")
    public CopilotController.ApiAiResponse<Map<String, Object>> createMulti(
            @RequestBody CreateMultiLoopRequest req) {
        MultiLoopOrchestrator.MultiLoopResult result = multiOrchestrator.createMultiLoop(
                req.getDocumentText(),
                req.getProviderCode(),
                req.getFlowType(),
                req.getSelectedInterfaceIds(),
                req.getMaxRounds() != null ? req.getMaxRounds() : 3);

        List<Map<String, Object>> subTaskList = result.getSubTasks().stream()
                .map(st -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("taskId", st.getTaskId());
                    m.put("interfaceId", st.getInterfaceId());
                    m.put("interfaceName", st.getInterfaceName());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parentTaskId", result.getParentTaskId());
        data.put("parentTaskNo", result.getParentTaskNo());
        data.put("subTasks", subTaskList);

        return CopilotController.ApiAiResponse.success(data);
    }

    /** Human decision — 决策由 orchestrator 落库并驱动状态机 */
    @PostMapping("/{taskId}/decide")
    public CopilotController.ApiAiResponse<Map<String, Object>> decide(
            @PathVariable Long taskId, @RequestBody DecisionRequest req) {
        req.setTaskId(taskId);
        orchestrator.decide(taskId, req);
        return CopilotController.ApiAiResponse.success(Map.of("taskId", taskId, "decision", req.getDecision()));
    }

    /** 用户中断正在执行的任务 */
    @PostMapping("/{taskId}/cancel")
    public CopilotController.ApiAiResponse<Map<String, Object>> cancel(@PathVariable Long taskId) {
        orchestrator.cancel(taskId);
        return CopilotController.ApiAiResponse.success(Map.of("taskId", taskId, "status", "CANCELLED"));
    }

    /**
     * Task status query — 轮询模式的唯一状态来源。
     * <p>
     * status=DECISION_POINT 时附带决策上下文：
     * decisionType（PUBLISH_CONFIRM / RECOVERY_EXHAUSTED）、decisionSummary、decisionOptions（列表）。
     * 契约（与任务 D 一致）：options 一律来自本接口，前端不再自行猜测。
     */
    @GetMapping("/{taskId}")
    public CopilotController.ApiAiResponse<Map<String, Object>> status(@PathVariable Long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return CopilotController.ApiAiResponse.error("Task not found", null);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.getId());
        data.put("taskNo", task.getTaskNo());
        data.put("status", task.getStatus());
        data.put("currentRound", task.getCurrentRound());
        data.put("maxRounds", task.getMaxRounds());
        data.put("providerCode", task.getProviderCode());
        data.put("flowType", task.getFlowType());
        if ("DECISION_POINT".equals(task.getStatus())) {
            data.put("decisionType", task.getDecisionType());
            data.put("decisionSummary", task.getDecisionSummary());
            data.put("decisionOptions", parseStringList(task.getDecisionOptions()));
        }
        return CopilotController.ApiAiResponse.success(data);
    }

    /**
     * 重试终态任务（启动补偿把重启前卡住的任务标成 FAILED 后可从此恢复）。
     * 活跃状态（ANALYZE/VALIDATE/DRYRUN/DIAGNOSE/DECISION_POINT）拒绝重试，避免双跑。
     */
    @PostMapping("/{taskId}/retry")
    public CopilotController.ApiAiResponse<Map<String, Object>> retry(@PathVariable Long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return CopilotController.ApiAiResponse.error("Task not found", null);
        }
        String status = task.getStatus();
        boolean active = Set.of("ANALYZE", "VALIDATE", "DRYRUN", "DIAGNOSE", "DECISION_POINT",
                "RUNNING", "DIAGNOSING").contains(status);
        if (active) {
            return CopilotController.ApiAiResponse.error("Task is active (status=" + status + "), cannot retry", null);
        }

        // 重置为 PENDING 后重启（orchestrator.start 内部条件更新保证并发下只启动一次）
        task.setStatus("PENDING");
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        // 清空上一次决策 — updateById 跳过 null 字段，需显式置 NULL
        taskMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiTask>lambdaUpdate()
                .set(AiTask::getDecisionResult, null)
                .set(AiTask::getDecisionTime, null)
                .eq(AiTask::getId, taskId));

        log.info("[LOOP] Retry requested  task={}  from={}", taskId, status);
        orchestrator.start(taskId);

        return CopilotController.ApiAiResponse.success(Map.of("taskId", taskId, "status", "PENDING"));
    }

    /** Get current analysis result for editing */
    @GetMapping("/{taskId}/result")
    public CopilotController.ApiAiResponse<Object> getResult(@PathVariable Long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return CopilotController.ApiAiResponse.error("Task not found", null);
        }
        if (task.getCurrentResult() == null) {
            return CopilotController.ApiAiResponse.error("No result available", null);
        }
        try {
            Object result = json.readValue(task.getCurrentResult(), Object.class);
            return CopilotController.ApiAiResponse.success(result);
        } catch (Exception e) {
            return CopilotController.ApiAiResponse.error("Failed to parse result: " + e.getMessage(), null);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) return Collections.emptyList();
        try {
            return (List<String>) json.readValue(jsonArray, Object.class);
        } catch (Exception e) {
            log.warn("[LOOP] Failed to parse decisionOptions: {}", jsonArray);
            return Collections.emptyList();
        }
    }

    // -- DTOs --

    public static class CreateMultiLoopRequest {
        private String documentText;
        private String providerCode;
        private String flowType;
        private List<String> selectedInterfaceIds;
        private Integer maxRounds;

        public String getDocumentText() { return documentText; }
        public void setDocumentText(String d) { this.documentText = d; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String p) { this.providerCode = p; }
        public String getFlowType() { return flowType; }
        public void setFlowType(String f) { this.flowType = f; }
        public List<String> getSelectedInterfaceIds() { return selectedInterfaceIds; }
        public void setSelectedInterfaceIds(List<String> ids) { this.selectedInterfaceIds = ids; }
        public Integer getMaxRounds() { return maxRounds; }
        public void setMaxRounds(Integer r) { this.maxRounds = r; }
    }

    public static class CreateLoopRequest {
        private String documentText;
        private String providerCode;
        private String flowType;
        private Integer maxRounds;

        public String getDocumentText() { return documentText; }
        public void setDocumentText(String d) { this.documentText = d; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String p) { this.providerCode = p; }
        public String getFlowType() { return flowType; }
        public void setFlowType(String f) { this.flowType = f; }
        public Integer getMaxRounds() { return maxRounds; }
        public void setMaxRounds(Integer r) { this.maxRounds = r; }
    }
}
