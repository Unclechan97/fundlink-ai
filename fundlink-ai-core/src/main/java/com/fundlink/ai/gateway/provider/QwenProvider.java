package com.fundlink.ai.gateway.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.gateway.LlmProvider;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import com.fundlink.ai.gateway.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component("qwen")
public class QwenProvider implements LlmProvider {

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public QwenProvider(
            @Value("${fundlink.llm.providers.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${fundlink.llm.providers.qwen.api-key:}") String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        log.info("[LLM] Qwen provider ready  model=qwen-plus");
    }

    @Override public String name() { return "qwen"; }
    @Override public boolean supports(String m) { return true; }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        log.info("[LLM] >>> Qwen call  traceId={}  promptLen={}", request.getTraceId(), request.getPrompt().length());

        try {
            var body = Map.of(
                "model", "qwen-plus",
                "messages", List.of(
                    Map.of("role", "system", "content", "你是资金接入系统专家。严格按JSON格式输出。"),
                    Map.of("role", "user", "content", request.getPrompt())
                ),
                "temperature", 0.1,
                "max_tokens", request.getMaxTokens()
            );

            var httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            var httpResp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            var tree = json.readTree(httpResp.body());
            String content = tree.get("choices").get(0).get("message").get("content").asText();
            int in = tree.get("usage").get("prompt_tokens").asInt();
            int out = tree.get("usage").get("completion_tokens").asInt();
            long elapsed = System.currentTimeMillis() - start;

            log.info("[LLM] <<< Qwen done  traceId={}  latency={}ms  tokens={}/{}.  contentLen={}",
                    request.getTraceId(), elapsed, in, out, content.length());
            log.debug("[LLM] <<< Qwen content  traceId={}\n{}",
                    request.getTraceId(), content.length() > 2000 ? content.substring(0, 2000) + "...TRUNCATED" : content);

            return LlmResponse.of(content, "qwen", "qwen-plus",
                    TokenUsage.of(in, out), elapsed);

        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LLM] <<< Qwen FAILED  traceId={}  latency={}ms  error={}",
                    request.getTraceId(), elapsed, e.getMessage());
            throw new RuntimeException("Qwen call failed: " + e.getMessage(), e);
        }
    }
}
