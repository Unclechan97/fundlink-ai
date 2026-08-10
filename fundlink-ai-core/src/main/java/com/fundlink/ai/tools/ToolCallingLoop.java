package com.fundlink.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * Tool Calling 循环编排。
 */
@Slf4j
public class ToolCallingLoop {

    private final LlmGateway llmGateway;
    private final ToolRegistry toolRegistry;
    private final int maxRounds;
    private final ObjectMapper json = new ObjectMapper();

    /** 可选监听器 — 排查链路写入 ai_agent_trace */
    private ToolLoopListener listener;

    public ToolCallingLoop(LlmGateway llmGateway, ToolRegistry toolRegistry) {
        this(llmGateway, toolRegistry, 3);
    }

    public ToolCallingLoop(LlmGateway llmGateway, ToolRegistry toolRegistry, int maxRounds) {
        this.llmGateway = llmGateway;
        this.toolRegistry = toolRegistry;
        this.maxRounds = maxRounds;
    }

    /** 设置可选监听器（排查链路用于写入 ai_agent_trace） */
    public void setListener(ToolLoopListener listener) {
        this.listener = listener;
    }

    public String run(String systemPrompt, String userPrompt, String traceId) {
        return run(systemPrompt, userPrompt, traceId, null);
    }

    /**
     * 执行 Tool Calling 循环，可选 listener。
     * @param listener 可选监听器，传 null 则行为与旧版一致
     */
    public String run(String systemPrompt, String userPrompt, String traceId,
                      ToolLoopListener listener) {
        this.listener = listener;
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));

        int round = 0;
        int totalToolCalls = 0;
        long startTime = System.currentTimeMillis();

        while (round < maxRounds) {
            round++;
            log.info("[ToolLoop] Round {}  traceId={}", round, traceId);
            long roundStart = System.currentTimeMillis();

            if (this.listener != null) {
                this.listener.onRoundStart(round);
            }

            LlmRequest request = LlmRequest.ofTools(messages, toolRegistry.toOpenAiTools(), traceId);
            LlmResponse response;
            try {
                response = llmGateway.chat(request);
            } catch (Exception e) {
                log.error("[ToolLoop] LLM failed in round {}: {}", round, e.getMessage());
                return "AI 服务暂时不可用: " + e.getMessage();
            }

            if (!response.isToolCall()) {
                log.info("[ToolLoop] Final answer  round={}", round);
                if (this.listener != null) {
                    this.listener.onComplete(round, totalToolCalls,
                            System.currentTimeMillis() - startTime);
                }
                return response.getContent();
            }

            List<ToolCall> toolCalls = response.getToolCalls();
            log.info("[ToolLoop] {} tool call(s)  round={}", toolCalls.size(), round);
            totalToolCalls += toolCalls.size();
            messages.add(buildAssistantMessage(toolCalls));

            for (ToolCall tc : toolCalls) {
                log.info("[ToolLoop] Executing: {}  args={}", tc.getName(), tc.getArguments());
                Tool tool = toolRegistry.find(tc.getName());
                ToolResult result;
                if (tool == null) {
                    result = ToolResult.error(tc.getId(), "未知工具: " + tc.getName());
                } else {
                    try {
                        result = new ToolResult(tc.getId(), tool.execute(tc));
                    } catch (Exception e) {
                        log.error("[ToolLoop] Tool {} failed: {}", tc.getName(), e.getMessage());
                        result = ToolResult.error(tc.getId(), "工具执行异常: " + e.getMessage());
                    }
                }
                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", result.getToolCallId(),
                        "content", result.getContent()
                ));

                // 回调监听器
                if (this.listener != null) {
                    String argsStr = "";
                    try {
                        argsStr = json.writeValueAsString(tc.getArguments());
                    } catch (Exception ignored) {}
                    String resultStr = result.getContent() != null ? result.getContent() : "";
                    this.listener.onToolCall(round, tc.getName(), argsStr, resultStr);
                }
            }

            if (this.listener != null) {
                this.listener.onRoundEnd(round, toolCalls.size(),
                        System.currentTimeMillis() - roundStart);
            }
        }

        log.info("[ToolLoop] Max rounds — forcing final answer");
        messages.add(Map.of("role", "user", "content",
                "请基于以上所有工具查询结果，给出最终的诊断分析（错误原因、影响范围、修复建议）。"));
        try {
            String finalAnswer = llmGateway.chat(LlmRequest.ofTools(messages, null, traceId)).getContent();
            if (this.listener != null) {
                this.listener.onComplete(round, totalToolCalls,
                        System.currentTimeMillis() - startTime);
            }
            return finalAnswer;
        } catch (Exception e) {
            return "诊断异常: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAssistantMessage(List<ToolCall> toolCalls) {
        List<Map<String, Object>> tcList = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            try {
                String argsJson = json.writeValueAsString(tc.getArguments());
                tcList.add(Map.of(
                        "id", tc.getId(),
                        "type", "function",
                        "function", Map.of("name", tc.getName(), "arguments", argsJson)
                ));
            } catch (Exception e) {
                log.warn("[ToolLoop] Serialize failed: {}", e.getMessage());
            }
        }
        return Map.of("role", "assistant", "tool_calls", tcList);
    }
}
