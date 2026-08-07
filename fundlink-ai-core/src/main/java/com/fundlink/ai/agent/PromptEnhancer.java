package com.fundlink.ai.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Few-shot 动态注入 — Agent 调用前从 RAG 检索相关案例
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptEnhancer {

    /**
     * 用 RAG 检索结果增强 Prompt
     * @param basePrompt 原始 Prompt
     * @param query 搜索查询(用任务关键词构建)
     * @return 增强后的 Prompt（包含 Few-shot 示例）
     */
    public String enhance(String basePrompt, String query) {
        List<String> examples = searchRag(query);
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

        log.info("Prompt enhanced with {} examples", examples.size());
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> searchRag(String query) {
        List<String> results = new ArrayList<>();
        try {
            URI uri = new URI("http://localhost:8000/search");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            String body = "{\"query\":\"" + query + "\",\"mode\":\"hybrid\",\"top_k\":3}";
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);

                // 简单解析: 取 results[].text 前200字符
                String json = response.toString();
                int textIdx = 0;
                while ((textIdx = json.indexOf("\"text\":\"", textIdx)) != -1) {
                    textIdx += 8;
                    int end = json.indexOf('"', textIdx);
                    if (end > textIdx) {
                        String snippet = json.substring(textIdx, Math.min(end, textIdx + 200));
                        if (snippet.length() > 20) results.add(snippet + "...");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("RAG search fallback: {}", e.getMessage());
        }
        return results;
    }
}
