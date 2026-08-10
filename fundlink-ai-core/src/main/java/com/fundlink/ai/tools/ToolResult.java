package com.fundlink.ai.tools;

/**
 * Tool 执行结果。
 */
public class ToolResult {

    private final String toolCallId;
    private final String content; // JSON 字符串

    public ToolResult(String toolCallId, String content) {
        this.toolCallId = toolCallId;
        this.content = content;
    }

    /** 返回 error 结果 */
    public static ToolResult error(String toolCallId, String errorMsg) {
        return new ToolResult(toolCallId, "{\"error\": \"" + escapeJson(errorMsg) + "\"}");
    }

    public String getToolCallId() { return toolCallId; }
    public String getContent() { return content; }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
