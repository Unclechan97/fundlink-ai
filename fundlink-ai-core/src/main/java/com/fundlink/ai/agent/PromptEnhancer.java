package com.fundlink.ai.agent;

import com.fundlink.ai.gateway.RagGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Few-shot 动态注入 — Agent 调用前从 RAG 检索相关案例
 * <p>
 * RAG 不可用时返回未增强的原始 Prompt（解析仍可进行），
 * 并通过 {@link #search(String)} 的 {@code available} 标志让调用方向用户明示。
 */
@Slf4j
@Service
public class PromptEnhancer {

    private final RagGateway ragGateway;

    public PromptEnhancer(RagGateway ragGateway) {
        this.ragGateway = ragGateway;
    }

    /**
     * RAG 语义检索（供 Agent 直接调用）
     * @param query 搜索查询
     * @return 检索结果（含 available 标志）
     */
    public RagGateway.SearchResult search(String query) {
        return ragGateway.search(query, 3);
    }

    /**
     * 用 RAG 检索结果增强 Prompt
     * @param basePrompt 原始 Prompt
     * @param query 搜索查询(用任务关键词构建)
     * @return 增强后的 Prompt（包含 Few-shot 示例；RAG 不可用时返回原 Prompt）
     */
    public String enhance(String basePrompt, String query) {
        log.info("[ENHANCER] Searching RAG for few-shot  query={}", query);
        RagGateway.SearchResult r = ragGateway.search(query, 3);
        List<String> examples = r.getResults();
        log.info("[ENHANCER] RAG returned {} examples  available={}", examples.size(), r.isAvailable());
        if (!r.isAvailable()) {
            log.warn("[ENHANCER] 知识库暂不可用 — 使用未增强的基础 Prompt 继续解析");
            return basePrompt;
        }
        if (examples.isEmpty()) {
            return basePrompt;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## 参考案例 (来自知识库)\n");
        sb.append("以下是从历史成功案例中检索到的相关知识，请参考这些模式:\n\n");
        for (int i = 0; i < examples.size(); i++) {
            sb.append("### 案例 ").append(i + 1).append("\n");
            sb.append(examples.get(i)).append("\n\n");
        }
        sb.append("---\n\n");
        sb.append(basePrompt);

        log.info("[ENHANCER] Prompt enhanced with {} examples", examples.size());
        return sb.toString();
    }
}
