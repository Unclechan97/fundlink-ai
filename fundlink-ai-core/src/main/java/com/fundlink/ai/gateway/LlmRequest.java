package com.fundlink.ai.gateway;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求参数
 */
public class LlmRequest {

    private String provider;
    private String model;
    private String systemPrompt;
    private String prompt;
    private Double temperature;
    private Integer maxTokens;
    private String taskType;  // simple/requirement/testgen/diagnosis/complex
    private String traceId;

    // Tool calling 扩展
    private List<Map<String, Object>> messages;  // 多轮对话消息列表（优先于 prompt）
    private List<Map<String, Object>> tools;     // OpenAI tools 格式
    private String toolChoice = "auto";          // "auto" | "none"

    public static LlmRequest of(String provider, String model, String prompt, String traceId) {
        LlmRequest r = new LlmRequest();
        r.provider = provider;
        r.model = model;
        r.prompt = prompt;
        r.traceId = traceId;
        return r;
    }

    /** 创建任务驱动请求 — provider=null，交由 SmartRouter 选择 */
    public static LlmRequest ofTask(String taskType, String prompt, String traceId) {
        LlmRequest r = new LlmRequest();
        r.taskType = taskType;
        r.prompt = prompt;
        r.traceId = traceId;
        return r;
    }

    /** 创建带 Tool Calling 的请求 */
    public static LlmRequest ofTools(List<Map<String, Object>> messages,
                                      List<Map<String, Object>> tools,
                                      String traceId) {
        LlmRequest r = new LlmRequest();
        r.messages = messages;
        r.tools = tools;
        r.traceId = traceId;
        r.taskType = "troubleshoot"; // Tool calling 目前只在排查场景使用
        return r;
    }

    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getSystemPrompt() { return systemPrompt; }
    public String getPrompt() { return prompt; }
    public String getTaskType() { return taskType; }
    public Double getTemperature() { return temperature; }
    public Integer getMaxTokens() { return maxTokens; }
    public String getTraceId() { return traceId; }
    public List<Map<String, Object>> getMessages() { return messages; }
    public List<Map<String, Object>> getTools() { return tools; }
    public String getToolChoice() { return toolChoice; }

    public void setTools(List<Map<String, Object>> tools) { this.tools = tools; }
    public void setToolChoice(String toolChoice) { this.toolChoice = toolChoice; }
}
