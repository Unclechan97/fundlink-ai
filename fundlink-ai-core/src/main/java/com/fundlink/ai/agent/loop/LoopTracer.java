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
        trace(taskId, traceId, phase, agentType,
                inputSummary, outputSummary, null, null, null,
                usage, durationMs, success, errorMsg);
    }

    /**
     * 记录执行轨迹（含 tool_calls JSON 和全文）。
     */
    public void trace(Long taskId, String traceId, String phase, String agentType,
                      String inputSummary, String outputSummary,
                      String inputText, String outputText, String toolCallsJson,
                      TokenUsage usage, long durationMs, boolean success, String errorMsg) {
        try {
            AiAgentTrace t = new AiAgentTrace();
            // nanoTime 确保同一 traceId 下的多条记录不冲突 UNIQUE 约束
            String safeTraceId = traceId != null ? traceId : "trace-" + randomSuffix();
            t.setTraceId(safeTraceId + "-" + randomSuffix());
            t.setTaskId(taskId);
            t.setPhase(truncate(phase, 20));  // ai_agent_trace.phase = VARCHAR(20)
            t.setAgentName(agentType);
            t.setAgentType(agentType);
            t.setStepName(phase);
            t.setInputSummary(inputSummary);
            t.setOutputSummary(outputSummary);
            t.setInputText(inputText);
            t.setOutputText(outputText);
            t.setToolCalls(toolCallsJson);
            t.setDurationMs((int) durationMs);
            t.setLatencyMs((int) durationMs);
            t.setStatus(success ? "SUCCESS" : "FAILED");
            t.setSuccess(success ? 1 : 0);
            t.setErrorMsg(errorMsg);
            t.setStartTime(LocalDateTime.now().minusSeconds(Math.max(0, durationMs / 1000)));
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
     * 写入单次 Tool Calling 记录 — 用于排查的 ToolLoopListener。
     */
    public void traceToolCall(Long taskId, String traceId, int round,
                               String toolName, String args, String result) {
        String phase = "TOOL_LOOP_R" + round;
        String toolCallsJson = buildToolCallJson(toolName, args, result);
        trace(taskId, traceId, phase, toolName,
                "参数: " + truncate(args, 480),
                truncate(result, 480),
                args, result, toolCallsJson,
                null, 0, true, null);
    }

    // ── helpers ──

    private static String randomSuffix() {
        return String.valueOf(System.nanoTime() % 10000000);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    /**
     * 构建 tool_calls JSON。
     * args 和 result 已是 JSON 字符串（来自 ObjectMapper / Tool.execute），
     * 需要原样嵌入，不能用 escapeJson 再转义一次。
     */
    private static String buildToolCallJson(String toolName, String args, String result) {
        // 确保 args / result 是有效的 JSON 片段，否则当作纯文本字符串
        String argsJson = isJsonLike(args) ? args : jsonString(args);
        String resultJson = isJsonLike(result) ? result : jsonString(result);

        return "{\"tool\":" + jsonString(toolName)
                + ",\"args\":" + argsJson
                + ",\"result\":" + resultJson + "}";
    }

    /** JSON 对象或数组开头 → 原样嵌入 */
    private static boolean isJsonLike(String s) {
        if (s == null || s.isBlank()) return false;
        String t = s.trim();
        return t.startsWith("{") || t.startsWith("[");
    }

    /** 将普通字符串包装为 JSON 字符串值 */
    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
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

    /**
     * 排查结果 → RAG 知识回写。
     * <p>
     * 将排查诊断结果作为案例写入知识库，供后续排查时检索参考。
     *
     * @param task     排查任务（需含 errorLog / providerCode 等）
     * @param analysis LLM 诊断结果全文
     * @param ragCount RAG 检索到的历史案例数
     */
    public void writebackTroubleshootKnowledge(AiTask task, String analysis, int ragCount) {
        if (analysis == null || analysis.isBlank()) return;

        try {
            // 截断分析文本避免 markdown 过大
            String summary = analysis.length() > 500
                    ? analysis.substring(0, 500) + "..."
                    : analysis;

            String providerCode = task.getProviderCode() != null
                    ? task.getProviderCode() : "UNKNOWN";

            String errorSnippet = "";
            if (task.getDocumentText() != null && !task.getDocumentText().isBlank()) {
                errorSnippet = task.getDocumentText().length() > 200
                        ? task.getDocumentText().substring(0, 200) + "..."
                        : task.getDocumentText();
            }

            String markdown = String.format("""
                ## 排查案例
                - **类型**: TROUBLESHOOT_CASE
                - **资金方**: %s
                - **检索到历史案例**: %d 条
                - **错误摘要**: %s
                - **诊断结果**: %s
                - **来源**: AI 问题排查自动归档
                - **关联任务**: %s
                """,
                    providerCode,
                    ragCount,
                    errorSnippet.replace("\n", " ").replace("\r", ""),
                    summary.replace("\n", " ").replace("\r", ""),
                    task.getTaskNo() != null ? task.getTaskNo() : "DIAG-UNKNOWN");

            boolean ok = ragGateway.upsertKnowledge("TROUBLESHOOT_CASE",
                    providerCode, markdown);
            if (ok) {
                log.info("[TRACE] Troubleshoot knowledge written to RAG  task={}  taskNo={}",
                        task.getId(), task.getTaskNo());
            }
        } catch (Exception e) {
            log.error("[TRACE] Troubleshoot knowledge writeback failed: {}", e.getMessage());
        }
    }
}
