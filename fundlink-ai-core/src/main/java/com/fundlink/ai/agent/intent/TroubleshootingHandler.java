package com.fundlink.ai.agent.intent;

import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * 问题排查意图处理器（架子）。
 *
 * 用户贴入报错日志/异常堆栈时，LLM 分析诊断。
 */
@Slf4j
public class TroubleshootingHandler implements IntentHandler {

    private final LlmGateway llmGateway;

    public TroubleshootingHandler(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public IntentType supportedType() {
        return IntentType.TROUBLESHOOTING;
    }

    @Override
    public Object handle(IntentContext ctx) {
        String prompt = """
                你是资金接入系统运维专家。请分析以下错误，给出：
                1. 错误原因
                2. 影响范围
                3. 修复建议

                错误信息：
                """ + ctx.getUserInput();

        try {
            LlmResponse resp = llmGateway.chat(
                    LlmRequest.ofTask("troubleshoot", prompt, ctx.getTraceId()));
            return TroubleshootResult.of(resp.getContent());
        } catch (Exception e) {
            log.error("[Troubleshoot] LLM call failed: {}", e.getMessage(), e);
            return TroubleshootResult.of("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
    }

    /**
     * 排查结果。
     */
    public static class TroubleshootResult {
        private String analysis;

        public static TroubleshootResult of(String analysis) {
            TroubleshootResult r = new TroubleshootResult();
            r.analysis = analysis;
            return r;
        }

        public String getAnalysis() { return analysis; }
        public void setAnalysis(String a) { this.analysis = a; }
    }
}
