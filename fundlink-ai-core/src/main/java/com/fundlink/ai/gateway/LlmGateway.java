package com.fundlink.ai.gateway;

/**
 * LLM 网关 — 统一入口，屏蔽底层 Provider 差异
 */
public interface LlmGateway {

    /**
     * 同步调用 LLM，包含路由、审计、降级
     */
    LlmResponse chat(LlmRequest request);
}
