package com.fundlink.ai.gateway;

/**
 * Token 用量统计
 */
public class TokenUsage {

    private int inputTokens;
    private int outputTokens;

    public static TokenUsage of(int inputTokens, int outputTokens) {
        TokenUsage t = new TokenUsage();
        t.inputTokens = inputTokens;
        t.outputTokens = outputTokens;
        return t;
    }

    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int totalTokens() { return inputTokens + outputTokens; }
}
