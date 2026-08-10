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

    public ToolCallingLoop(LlmGateway llmGateway, ToolRegistry toolRegistry) {
        this(llmGateway, toolRegistry, 3);
    }

    public ToolCallingLoop(LlmGateway llmGateway, ToolRegistry toolRegistry, int maxRounds) {
        this.llmGateway = llmGateway;
        this.toolRegistry = toolRegistry;
        this.maxRounds = maxRounds;
    }

    public String run(String systemPrompt, String userPrompt, String traceId) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userPrompt));

        int round = 0;
        while (round < maxRounds) {
            round++;
            log.info("[ToolLoop] Round {}  traceId={}", round, traceId);

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
                return response.getContent();
            }

            List<ToolCall> toolCalls = response.getToolCalls();
            log.info("[ToolLoop] {} tool call(s)  round={}", toolCalls.size(), round);
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
            }
        }

        log.info("[ToolLoop] Max rounds — forcing final answer");
        messages.add(Map.of("role", "user", "content",
                "请基于以上所有工具查询结果，给出最终的诊断分析（错误原因、影响范围、修复建议）。"));
        try {
            return llmGateway.chat(LlmRequest.ofTools(messages, null, traceId)).getContent();
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
