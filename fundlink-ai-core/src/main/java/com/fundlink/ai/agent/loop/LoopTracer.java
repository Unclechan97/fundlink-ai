package com.fundlink.ai.agent.loop;

import com.fundlink.ai.entity.AiAgentTrace;
import com.fundlink.ai.gateway.TokenUsage;
import com.fundlink.ai.mapper.AiAgentTraceMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Loop 追踪器 — 记录每轮执行轨迹 (设计 §9)
 * <p>
 * 数据飞轮已切断（2026-08）：不再回写 RAG 知识库，仅保留轨迹记录。
 */
@Slf4j
@Service
public class LoopTracer {

    private final AiAgentTraceMapper traceMapper;

    public LoopTracer(AiAgentTraceMapper traceMapper) {
        this.traceMapper = traceMapper;
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
}
