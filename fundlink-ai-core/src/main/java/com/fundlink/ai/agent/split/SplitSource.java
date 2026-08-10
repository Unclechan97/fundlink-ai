package com.fundlink.ai.agent.split;

/**
 * 拆分来源 — 记录使用了哪种策略成功拆分。
 */
public enum SplitSource {
    /** Strategy 1: Markdown 标题匹配 */
    MARKDOWN_HEADING,
    /** Strategy 2: 分隔线匹配 */
    DELIMITER,
    /** Strategy 3: 端点锚点匹配 */
    ANCHOR,
    /** Strategy 4: 全文档兜底（退化为单接口） */
    FULL_DOC
}
