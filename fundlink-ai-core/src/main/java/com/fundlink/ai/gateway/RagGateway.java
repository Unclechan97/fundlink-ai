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

/**
 * RAG HTTP 调用统一封装 — /search + /knowledge/upsert
 * <p>
 * 鉴权已移除，直连 RAG API。
 * PromptEnhancer / KnowledgeAutoWriter / LoopTracer 共用。
 */
@Slf4j
@Service
public class RagGateway {

    private final String ragBaseUrl;
    private final ObjectMapper json = new ObjectMapper();

    public RagGateway(@Value("${fundlink.rag.base-url:http://localhost:8000}") String ragBaseUrl) {
        this.ragBaseUrl = ragBaseUrl.endsWith("/") ? ragBaseUrl.substring(0, ragBaseUrl.length() - 1) : ragBaseUrl;
    }

    /** RAG 语义检索 */
    public List<String> search(String query, int topK) {
        List<String> results = new ArrayList<>();
        try {
            String body = json.writeValueAsString(
                    java.util.Map.of("query", query, "mode", "hybrid", "top_k", topK));
            String resp = postJson("/search", body);
            if (resp == null) return results;

            JsonNode root = json.readTree(resp);
            JsonNode resultsNode = root.get("results");
            if (resultsNode != null && resultsNode.isArray()) {
                for (JsonNode item : resultsNode) {
                    String text = item.path("text").asText(null);
                    if (text != null && text.length() > 20) {
                        results.add(text.length() > 200 ? text.substring(0, 200) + "..." : text);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[RAG] Search failed: {} (baseUrl={})", e.getMessage(), ragBaseUrl);
        }
        return results;
    }

    /** 知识条目写回 RAG */
    public boolean upsertKnowledge(String kind, String providerCode, String markdown) {
        try {
            String body = json.writeValueAsString(
                    java.util.Map.of("kind", kind, "provider_code", providerCode, "markdown", markdown));
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
