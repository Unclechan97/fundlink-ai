package com.fundlink.ai.gateway;

/**
 * LLM Provider 适配器接口 — 每个 LLM 厂商实现此接口
 */
public interface LlmProvider {

    /** Provider 标识 */
    String name();

    /** 支持的模型列表 */
    boolean supports(String model);

    /** 调用 LLM */
    LlmResponse chat(LlmRequest request);
}
