package com.fundlink.ai.tools;

import java.util.Map;

/**
 * Tool 定义 — OpenAI function calling 格式。
 */
public class ToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters; // JSON Schema

    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    /** 转为 OpenAI tools 数组中的单个元素 */
    public Map<String, Object> toOpenAiFormat() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", parameters
                )
        );
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getParameters() { return parameters; }
}
