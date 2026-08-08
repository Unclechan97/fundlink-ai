package com.fundlink.ai.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * 硅基流动 (SiliconFlow) — 免费 Qwen3-8B 模型，OpenAI 兼容 API
 */
@Slf4j
@Component("siliconflow")
public class SiliconFlowProvider implements LlmProvider {

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public SiliconFlowProvider(
            @Value("${fundlink.llm.providers.siliconflow.base-url:https://api.siliconflow.cn/v1}") String baseUrl,
            @Value("${fundlink.llm.providers.siliconflow.api-key:}") String apiKey) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        log.info("[LLM] SiliconFlow provider ready  model=Qwen/Qwen3-8B  baseUrl={}", baseUrl);
    }

    @Override public String name() { return "siliconflow"; }
    @Override public boolean supports(String m) { return true; }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        String model = request.getModel() != null ? request.getModel() : "Qwen/Qwen3-8B";
        String systemPrompt = request.getSystemPrompt() != null
                ? request.getSystemPrompt()
                : "你是资金接入系统专家。严格按JSON格式输出。";
        double temperature = request.getTemperature() != null ? request.getTemperature() : 0.1;
        int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 4096;

        log.info("[LLM] >>> SiliconFlow call  traceId={}  model={}  promptLen={}",
                request.getTraceId(), model,
                request.getPrompt() != null ? request.getPrompt().length() : 0);

        try {
            var body = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", request.getPrompt())
                ),
                "temperature", temperature,
                "max_tokens", maxTokens
            );

            var httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(120))
                    .build();

            var httpResp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            int statusCode = httpResp.statusCode();
            String respBody = httpResp.body();

            if (statusCode != 200) {
                String errorDetail = extractError(respBody, statusCode);
                long elapsed = System.currentTimeMillis() - start;
                log.error("[LLM] <<< SiliconFlow HTTP {}  traceId={}  latency={}ms  error={}",
                        statusCode, request.getTraceId(), elapsed, errorDetail);
                throw new RuntimeException(String.format(
                        "SiliconFlow API returned HTTP %d: %s", statusCode, errorDetail));
            }

            var tree = json.readTree(respBody);
            JsonNode choices = tree.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                long elapsed = System.currentTimeMillis() - start;
                log.error("[LLM] <<< SiliconFlow empty choices  traceId={}  latency={}ms  body={}",
                        request.getTraceId(), elapsed,
                        respBody.length() > 500 ? respBody.substring(0, 500) : respBody);
                throw new RuntimeException(
                        "SiliconFlow returned empty choices — check API key and model availability");
            }

            String content = choices.get(0).get("message").get("content").asText();
            JsonNode usage = tree.get("usage");
            int in = usage != null ? usage.path("prompt_tokens").asInt(0) : 0;
            int out = usage != null ? usage.path("completion_tokens").asInt(0) : 0;
            long elapsed = System.currentTimeMillis() - start;

            log.info("[LLM] <<< SiliconFlow done  traceId={}  latency={}ms  tokens={}/{}  contentLen={}",
                    request.getTraceId(), elapsed, in, out,
                    content != null ? content.length() : 0);
            log.debug("[LLM] <<< SiliconFlow content  traceId={}\n{}",
                    request.getTraceId(), content);

            return LlmResponse.of(content, "siliconflow", model,
                    TokenUsage.of(in, out), elapsed);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LLM] <<< SiliconFlow FAILED  traceId={}  latency={}ms  error={}",
                    request.getTraceId(), elapsed, e.getMessage());
            throw new RuntimeException("SiliconFlow call failed: " + e.getMessage(), e);
        }
    }

    private String extractError(String respBody, int statusCode) {
        try {
            JsonNode tree = json.readTree(respBody);
            JsonNode error = tree.get("error");
            if (error != null) {
                String msg = error.path("message").asText(null);
                String code = error.path("code").asText(null);
                if (msg != null) return code != null ? code + ": " + msg : msg;
            }
            return respBody.length() > 300 ? respBody.substring(0, 300) + "..." : respBody;
        } catch (Exception e) {
            return "HTTP " + statusCode + " — "
                    + (respBody.length() > 200 ? respBody.substring(0, 200) + "..." : respBody);
        }
    }
}
