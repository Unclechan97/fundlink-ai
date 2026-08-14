package com.fundlink.ai.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RAG HTTP 调用统一封装 — /search + /knowledge/upsert
 * <p>
 * 服务间鉴权：请求头 {@code X-Internal-Token: ${fundlink.rag.internal-key}}，
 * 与 RAG 侧环境变量 {@code RAG_INTERNAL_KEY} 同值；未配置时 WARN 并继续直连（向后兼容）。
 * <p>
 * 数据飞轮已切断（2026-08）：不再自动写回知识库；
 * {@link #upsertKnowledge} 保留作为 /knowledge/upsert 契约的客户端。
 */
@Slf4j
@Service
public class RagGateway {

    private final String ragBaseUrl;
    private final String internalKey;
    private final ObjectMapper json = new ObjectMapper();

    public RagGateway(@Value("${fundlink.rag.base-url:http://localhost:8000}") String ragBaseUrl,
                      @Value("${fundlink.rag.internal-key:}") String internalKey) {
        this.ragBaseUrl = ragBaseUrl.endsWith("/") ? ragBaseUrl.substring(0, ragBaseUrl.length() - 1) : ragBaseUrl;
        this.internalKey = internalKey != null ? internalKey.trim() : "";
        if (this.internalKey.isEmpty()) {
            log.warn("[RAG] fundlink.rag.internal-key 未配置 — 请求不带 X-Internal-Token，RAG 侧可能拒绝");
        }
    }

    /** RAG 检索结果 — available=false 表示知识库当前不可用（连接失败 / HTTP 错误 / degraded 降级） */
    public static class SearchResult {
        private final boolean available;
        private final List<String> results;

        private SearchResult(boolean available, List<String> results) {
            this.available = available;
            this.results = results;
        }

        public static SearchResult available(List<String> results) {
            return new SearchResult(true, results != null ? results : List.of());
        }

        public static SearchResult unavailable() {
            return new SearchResult(false, List.of());
        }

        public boolean isAvailable() { return available; }
        public List<String> getResults() { return results; }
    }

    /** RAG 语义检索 — 永不抛异常，可用性通过 {@link SearchResult#isAvailable()} 表达 */
    public SearchResult search(String query, int topK) {
        try {
            String body = json.writeValueAsString(
                    Map.of("query", query, "mode", "hybrid", "top_k", topK));
            String resp = postJson("/search", body);
            if (resp == null) return SearchResult.unavailable();

            JsonNode root = json.readTree(resp);
            // 契约：RAG 降级时返回 degraded=true — 视为不可用，调用方不得拿着降级上下文继续生成诊断
            if (root.path("degraded").asBoolean(false)) {
                log.warn("[RAG] /search 返回 degraded=true — 知识库降级，本次检索视为不可用");
                return SearchResult.unavailable();
            }

            List<String> results = new ArrayList<>();
            JsonNode resultsNode = root.get("results");
            if (resultsNode != null && resultsNode.isArray()) {
                for (JsonNode item : resultsNode) {
                    String text = item.path("text").asText(null);
                    if (text != null && text.length() > 20) {
                        results.add(text.length() > 200 ? text.substring(0, 200) + "..." : text);
                    }
                }
            }
            return SearchResult.available(results);
        } catch (Exception e) {
            log.warn("[RAG] Search failed: {} (baseUrl={})", e.getMessage(), ragBaseUrl);
            return SearchResult.unavailable();
        }
    }

    /** 知识条目写回 RAG（飞轮已切断，当前无调用方；保留作为契约客户端） */
    public boolean upsertKnowledge(String kind, String providerCode, String markdown) {
        try {
            String body = json.writeValueAsString(
                    Map.of("kind", kind, "provider_code", providerCode, "markdown", markdown));
            String resp = postJson("/knowledge/upsert", body);
            return resp != null;
        } catch (Exception e) {
            log.warn("[RAG] Knowledge upsert failed: {}", e.getMessage());
            return false;
        }
    }

    // -- HTTP helpers --

    private String postJson(String path, String body) throws Exception {
        URI uri = new URI(ragBaseUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        if (!internalKey.isEmpty()) {
            conn.setRequestProperty("X-Internal-Token", internalKey);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() == 200) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }

        log.warn("[RAG] HTTP {} for {}", conn.getResponseCode(), path);
        return null;
    }
}
