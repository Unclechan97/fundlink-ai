package com.fundlink.ai.agent.requirement;

import com.fundlink.ai.agent.diagnosis.DiagnosisResult;

import java.util.List;

/**
 * 需求解析 Agent — 输入资金方接口文档，输出结构化配置
 */
public interface RequirementAgent {

    /**
     * 解析接口文档
     * @param documentText   接口文档原文
     * @param providerCode   资金方编码
     * @param previousErrors 上一轮诊断结果 (首轮传 null 或 empty list)
     * @return 结构化解析结果
     */
    RequirementResult analyze(String documentText, String providerCode,
                              List<DiagnosisResult> previousErrors);
}
