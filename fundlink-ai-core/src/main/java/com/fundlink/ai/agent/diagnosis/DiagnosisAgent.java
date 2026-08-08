package com.fundlink.ai.agent.diagnosis;

import java.util.Map;

/**
 * 诊断 Agent — 分析验证/干跑失败原因
 */
public interface DiagnosisAgent {

    /**
     * @param phase           失败阶段: "VALIDATE" | "DRYRUN"
     * @param errorDescription 错误描述文本
     * @param context         上下文 (flowDsl JSON, fieldMappings, round, providerCode 等)
     * @return 诊断结果 (含修正建议)
     */
    DiagnosisResult diagnose(String phase, String errorDescription, Map<String, Object> context);
}
