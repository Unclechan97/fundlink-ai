package com.fundlink.ai.gateway;

import com.fundlink.ai.tools.ToolCall;

import java.util.List;

/**
 * LLM 响应结果
 */
public class LlmResponse {

    private String content;
    private String provider;
    private String model;
    private TokenUsage tokenUsage;
    private long latencyMs;

    private List<ToolCall> toolCalls;

    public static LlmResponse of(String content, String provider, String model,
                                  TokenUsage tokenUsage, long latencyMs) {
        LlmResponse r = new LlmResponse();
        r.content = content;
        r.provider = provider;
        r.model = model;
        r.tokenUsage = tokenUsage;
        r.latencyMs = latencyMs;
        return r;
    }

    public static LlmResponse ofToolCalls(List<ToolCall> toolCalls, String provider,
                                           String model, TokenUsage tokenUsage, long latencyMs) {
        LlmResponse r = new LlmResponse();
        r.toolCalls = toolCalls;
        r.provider = provider;
        r.model = model;
        r.tokenUsage = tokenUsage;
        r.latencyMs = latencyMs;
        return r;
    }

    public boolean isToolCall() { return toolCalls != null && !toolCalls.isEmpty(); }

    public String getContent() { return content; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public TokenUsage getTokenUsage() { return tokenUsage; }
    public long getLatencyMs() { return latencyMs; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
}
