package com.fundlink.ai.tools;

import java.util.Map;

/**
 * LLM 返回的 Tool 调用请求。
 */
public class ToolCall {

    private final String id;
    private final String name;
    private final Map<String, Object> arguments;

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public Map<String, Object> getArguments() { return arguments; }

    /** 从 arguments 中取字符串参数 */
    public String arg(String key) {
        Object val = arguments.get(key);
        return val != null ? val.toString() : null;
    }

    /** 从 arguments 中取整数参数 */
    public Integer argInt(String key) {
        Object val = arguments.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val instanceof String) {
            try { return Integer.parseInt((String) val); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
