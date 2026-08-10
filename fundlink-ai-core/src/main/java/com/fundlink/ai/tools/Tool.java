package com.fundlink.ai.tools;

/**
 * Tool 接口 — 每个 Tool 实现此接口。
 */
public interface Tool {

    /** Tool 元数据定义 */
    ToolDefinition getDefinition();

    /** 执行 Tool，入参为 LLM 返回的 arguments，返回 JSON 字符串 */
    String execute(ToolCall call);
}
