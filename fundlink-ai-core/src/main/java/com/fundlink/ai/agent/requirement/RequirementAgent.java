package com.fundlink.ai.agent.requirement;

/**
 * 需求解析 Agent — 输入资金方接口文档，输出结构化配置
 */
public interface RequirementAgent {

    /**
     * 解析接口文档
     * @param documentText 接口文档原文
     * @param providerCode 资金方编码
     * @return 结构化解析结果
     */
    RequirementResult analyze(String documentText, String providerCode);
}
