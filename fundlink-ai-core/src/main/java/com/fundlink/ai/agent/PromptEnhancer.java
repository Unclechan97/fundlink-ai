package com.fundlink.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Few-shot 动态注入 — Agent 调用前从 RAG 检索相关案例
 */
@Slf4j
@Service
public class PromptEnhancer {

    private final String ragBaseUrl;
    private final ObjectMapper objectMapper;
    private volatile String cachedToken;

    public PromptEnhancer(
            @Value("${fundlink.rag.base-url:http://localhost:8000}") String ragBaseUrl) {
        this.ragBaseUrl = ragBaseUrl.endsWith("/") ? ragBaseUrl.substring(0, ragBaseUrl.length() - 1) : ragBaseUrl;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 用 RAG 检索结果增强 Prompt
     * @param basePrompt 原始 Prompt
     * @param query 搜索查询(用任务关键词构建)
     * @return 增强后的 Prompt（包含 Few-shot 示例）
     */
    public String enhance(String basePrompt, String query) {
        log.info("[ENHANCER] Searching RAG for few-shot  query={}", query);
        List<String> examples = search(query);
        log.info("[ENHANCER] RAG returned {} examples", examples.size());
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

    public List<String> search(String query) {
        List<String> results = new ArrayList<>();
        try {
            String token = ensureToken();
            URI uri = new URI(ragBaseUrl + "/search");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("query", query, "mode", "hybrid", "top_k", 3));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                JsonNode root = objectMapper.readTree(response.toString());
                JsonNode resultsNode = root.get("results");
                if (resultsNode != null && resultsNode.isArray()) {
                    for (JsonNode item : resultsNode) {
                        String text = item.path("text").asText(null);
                        if (text != null && text.length() > 20) {
                            results.add(text.length() > 200
                                    ? text.substring(0, 200) + "..."
                                    : text);
                        }
                    }
                }
            } else {
                // Read error body for diagnostics
                try {
                    BufferedReader errReader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                    StringBuilder errBody = new StringBuilder();
                    String errLine;
                    while ((errLine = errReader.readLine()) != null) errBody.append(errLine);
                    log.warn("[ENHANCER] RAG search returned HTTP {} body={}",
                            conn.getResponseCode(), errBody);
                } catch (Exception ignored) {
                    log.warn("[ENHANCER] RAG search returned HTTP {}", conn.getResponseCode());
                }
                // Token might be expired — clear and retry once
                cachedToken = null;
            }
        } catch (Exception e) {
            log.warn("[ENHANCER] RAG search failed: {} (baseUrl={})", e.getMessage(), ragBaseUrl);
        }
        return results;
    }

    private String ensureToken() throws Exception {
        if (cachedToken != null) return cachedToken;

        URI uri = new URI(ragBaseUrl + "/token");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        String body = "{\"username\":\"fundlink-ai\",\"role\":\"superadmin\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() == 200) {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) response.append(line);

            JsonNode root = objectMapper.readTree(response.toString());
            cachedToken = root.path("token").asText(null);
            if (cachedToken == null) {
                throw new RuntimeException("RAG /token response missing token field");
            }
            log.info("[ENHANCER] RAG token obtained successfully");
        } else {
            throw new RuntimeException("RAG /token returned HTTP " + conn.getResponseCode());
        }
        return cachedToken;
    }
}
