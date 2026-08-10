package com.fundlink.ai.agent.intent;

/**
 * 意图处理器 — 策略接口。
 * 每个 IntentType 对应一个 Handler 实现。
 */
public interface IntentHandler {

    /** 该 Handler 支持的意图类型 */
    IntentType supportedType();

    /** 处理用户输入，返回结果（类型由各实现定义） */
    Object handle(IntentContext ctx);
}
