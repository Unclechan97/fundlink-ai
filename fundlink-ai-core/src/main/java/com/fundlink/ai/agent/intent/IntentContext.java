package com.fundlink.ai.agent.intent;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图处理上下文 — 携带用户输入、traceId 等。
 */
public class IntentContext {

    private String userInput;
    private String traceId;
    private String providerCode;
    private Map<String, Object> attributes = new HashMap<>();

    public static IntentContext of(String userInput, String traceId) {
        IntentContext ctx = new IntentContext();
        ctx.userInput = userInput;
        ctx.traceId = traceId;
        return ctx;
    }

    // ── Getters / Setters ──

    public String getUserInput() { return userInput; }
    public void setUserInput(String s) { this.userInput = s; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String s) { this.traceId = s; }

    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String s) { this.providerCode = s; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> m) { this.attributes = m; }

    public <T> T getAttribute(String key) {
        @SuppressWarnings("unchecked")
        T val = (T) attributes.get(key);
        return val;
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }
}
