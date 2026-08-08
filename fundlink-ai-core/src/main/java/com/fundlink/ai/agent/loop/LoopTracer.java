package com.fundlink.ai.agent.loop;

import com.fundlink.ai.agent.diagnosis.DiagnosisResult;
import com.fundlink.ai.entity.AiAgentTrace;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.gateway.RagGateway;
import com.fundlink.ai.gateway.TokenUsage;
import com.fundlink.ai.mapper.AiAgentTraceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Loop 追踪器 — 记录每轮执行轨迹 + RAG 知识回写 (设计 §9)
 */
@Slf4j
@Service
public class LoopTracer {

    private final AiAgentTraceMapper traceMapper;
    private final RagGateway ragGateway;

    public LoopTracer(AiAgentTraceMapper traceMapper, RagGateway ragGateway) {
        this.traceMapper = traceMapper;
        this.ragGateway = ragGateway;
    }

    /**
     * 记录一轮执行轨迹到 ai_agent_trace
     */
    public void trace(Long taskId, String traceId, String phase, String agentType,
                      String inputSummary, String outputSummary, TokenUsage usage,
                      long durationMs, boolean success, String errorMsg) {
        try {
            AiAgentTrace t = new AiAgentTrace();
            // traceId 可能重跑时重复 — 加时间戳后缀确保唯一
            t.setTraceId(traceId + "-" + System.currentTimeMillis() % 100000);
            t.setTaskId(taskId);
            t.setPhase(phase);
            t.setAgentName(agentType);
            t.setAgentType(agentType);
            t.setStepName(phase);
            t.setInputSummary(inputSummary);
            t.setOutputSummary(outputSummary);
            t.setDurationMs((int) durationMs);
            t.setLatencyMs((int) durationMs);
            t.setStatus(success ? "SUCCESS" : "FAILED");
            t.setSuccess(success ? 1 : 0);
            t.setErrorMsg(errorMsg);
            t.setStartTime(LocalDateTime.now().minusSeconds(durationMs / 1000));
            t.setEndTime(LocalDateTime.now());
            if (usage != null) {
                try {
                    t.setTokenUsage("{\"input\":" + usage.getInputTokens()
                            + ",\"output\":" + usage.getOutputTokens()
                            + ",\"total\":" + usage.totalTokens() + "}");
                } catch (Exception ignored) {}
            }
            traceMapper.insert(t);
        } catch (Exception e) {
            log.error("[TRACE] Failed to write trace record: {}", e.getMessage());
        }
    }

    /**
     * 修正成功 → RAG 知识回写
     */
    public void writebackKnowledge(AiTask task, DiagnosisResult diagnosis) {
        if (diagnosis == null || diagnosis.getRootCause() == null) return;

        try {
            String markdown = String.format("""
                ## 闭环修正规则
                - **类型**: DIAGNOSIS_FIX
                - **阶段**: %s
                - **根因**: %s
                - **修正建议**: %s
                - **资金方**: %s
                - **来源**: Agent Loop 闭环自动修正
                """,
                    diagnosis.getPhase(),
                    diagnosis.getRootCause(),
                    diagnosis.getFixSuggestion() != null ? diagnosis.getFixSuggestion() : "",
                    task.getProviderCode() != null ? task.getProviderCode() : "UNKNOWN");

            boolean ok = ragGateway.upsertKnowledge("DIAGNOSIS_FIX",
                    task.getProviderCode(), markdown);
            if (ok) {
                log.info("[TRACE] Knowledge written back to RAG  task={}  rootCause={}",
                        task.getId(), diagnosis.getRootCause().substring(0,
                                Math.min(60, diagnosis.getRootCause().length())));
            }
        } catch (Exception e) {
            log.error("[TRACE] Knowledge writeback failed: {}", e.getMessage());
        }
    }
}
