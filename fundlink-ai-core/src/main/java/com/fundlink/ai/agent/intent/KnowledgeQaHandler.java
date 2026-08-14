package com.fundlink.ai.agent.intent;

import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import com.fundlink.ai.gateway.RagGateway;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 知识问答意图处理器。
 *
 * 用户询问业务知识/产品规则时，先检索 RAG 知识库，再交给 LLM 回答。
 */
@Slf4j
public class KnowledgeQaHandler implements IntentHandler {

    private final LlmGateway llmGateway;
    private final RagGateway ragGateway;

    public KnowledgeQaHandler(LlmGateway llmGateway, RagGateway ragGateway) {
        this.llmGateway = llmGateway;
        this.ragGateway = ragGateway;
    }

    @Override
    public IntentType supportedType() {
        return IntentType.KNOWLEDGE_QA;
    }

    @Override
    public Object handle(IntentContext ctx) {
        // 1. RAG 检索相关知识 — 不可用时直接向用户明示，不做无依据回答
        RagGateway.SearchResult ragResult = ragGateway.search(ctx.getUserInput(), 3);
        List<String> ragExamples = ragResult.getResults();
        log.info("[QA] RAG returned {} examples  available={}", ragExamples.size(), ragResult.isAvailable());
        if (!ragResult.isAvailable()) {
            log.warn("[QA] RAG 不可用 — 返回明示提示");
            return QaResult.of("知识库暂不可用，请稍后重试。");
        }

        // 2. 构建 Prompt（注入 RAG 上下文）
        StringBuilder sb = new StringBuilder();
        if (!ragExamples.isEmpty()) {
            sb.append("## 知识库参考\n");
            sb.append("以下是从知识库中检索到的相关内容，请参考这些信息回答问题:\n\n");
            for (int i = 0; i < ragExamples.size(); i++) {
                sb.append("### 参考 ").append(i + 1).append("\n");
                sb.append(ragExamples.get(i)).append("\n\n");
            }
            sb.append("---\n\n");
        }
        sb.append("你是资金接入系统专家。请参考上述知识库内容，用中文回答用户问题。\n\n");
        sb.append("用户问题：\n").append(ctx.getUserInput());
        String prompt = sb.toString();

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
