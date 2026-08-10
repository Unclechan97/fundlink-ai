package com.fundlink.ai.agent.intent;

import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 知识问答意图处理器（架子）。
 *
 * 用户询问业务知识/产品规则时，LLM 直接回答。
 */
@Slf4j
public class KnowledgeQaHandler implements IntentHandler {

    private final LlmGateway llmGateway;

    public KnowledgeQaHandler(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public IntentType supportedType() {
        return IntentType.KNOWLEDGE_QA;
    }

    @Override
    public Object handle(IntentContext ctx) {
        String prompt = "你是资金接入系统专家。请用中文回答用户问题。\n\n用户问题：\n" + ctx.getUserInput();

        try {
            LlmResponse resp = llmGateway.chat(
                    LlmRequest.ofTask("qa", prompt, ctx.getTraceId()));
            return QaResult.of(resp.getContent());
        } catch (Exception e) {
            log.error("[QA] LLM call failed: {}", e.getMessage(), e);
            return QaResult.of("抱歉，AI 服务暂时不可用，请稍后重试。");
        }
    }

    /**
     * 问答结果。
     */
    public static class QaResult {
        private String answer;

        public static QaResult of(String answer) {
            QaResult r = new QaResult();
            r.answer = answer;
            return r;
        }

        public String getAnswer() { return answer; }
        public void setAnswer(String a) { this.answer = a; }
    }
}
