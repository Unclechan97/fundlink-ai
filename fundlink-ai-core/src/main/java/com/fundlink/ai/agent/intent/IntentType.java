package com.fundlink.ai.agent.intent;

/**
 * 意图类型枚举。
 */
public enum IntentType {
    /** 接口开发 — 进入拆分 + 生成流程 */
    INTERFACE_DEV("接口开发"),
    /** 知识问答 — LLM 直接回答业务问题 */
    KNOWLEDGE_QA("知识问答"),
    /** 问题排查 — LLM 分析报错/日志 */
    TROUBLESHOOTING("问题排查"),
    /** 无法识别 */
    UNKNOWN("未知");

    private final String displayName;

    IntentType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
