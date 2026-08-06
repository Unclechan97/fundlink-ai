package com.fundlink.ai.gateway;

/**
 * LLM 请求参数
 */
public class LlmRequest {

    private String provider;
    private String model;
    private String systemPrompt;
    private String prompt;
    private double temperature = 0.3;
    private int maxTokens = 4096;
    private String traceId;

    public static LlmRequest of(String provider, String model, String prompt, String traceId) {
        LlmRequest r = new LlmRequest();
        r.provider = provider;
        r.model = model;
        r.prompt = prompt;
        r.traceId = traceId;
        return r;
    }

    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getPrompt() { return prompt; }
    public double getTemperature() { return temperature; }
    public int getMaxTokens() { return maxTokens; }
    public String getTraceId() { return traceId; }
}
