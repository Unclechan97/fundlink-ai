package com.fundlink.ai.gateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.gateway.LlmProvider;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import com.fundlink.ai.gateway.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import com.fundlink.ai.tools.ToolCall;

/**
 * OpenAI 兼容 API Provider — 配置驱动，支持任意 OpenAI 兼容的 LLM 服务。
 * <p>
 * 当前已适配：SiliconFlow / 阿里云 DashScope(Qwen) / DeepSeek。
 * 新增 provider 只需在 application.yml 添加配置 + LlmProviderConfig 注册 bean。
 */
@Slf4j
public class OpenAiCompatibleProvider implements LlmProvider {

    private static final String DEFAULT_SYSTEM_PROMPT = "你是资金接入系统专家。严格按JSON格式输出。";

    private final String name;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final Duration requestTimeout;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;

    public OpenAiCompatibleProvider(String name, String baseUrl,
                                     String apiKey, String defaultModel,
                                     Duration connectTimeout, Duration requestTimeout) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        log.info("[LLM] Provider ready  name={}  model={}  baseUrl={}  connectTimeout={}  requestTimeout={}",
                name, defaultModel, baseUrl, connectTimeout, requestTimeout);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supports(String model) {
        return true;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        long start = System.currentTimeMillis();
        String model = request.getModel() != null ? request.getModel() : defaultModel;
        String systemPrompt = request.getSystemPrompt() != null
                ? request.getSystemPrompt() : DEFAULT_SYSTEM_PROMPT;
        double temperature = request.getTemperature() != null ? request.getTemperature() : 0.1;
        int maxTokens = request.getMaxTokens() != null ? request.getMaxTokens() : 4096;

        log.info("[LLM] >>> {} call  traceId={}  model={}  promptLen={}  tools={}",
                name, request.getTraceId(), model,
                request.getPrompt() != null ? request.getPrompt().length() : 0,
                request.getTools() != null ? request.getTools().size() : 0);

        try {
            // 构建 messages：优先用 messages 列表（多轮），否则用 systemPrompt + prompt
            List<Map<String, Object>> messages;
            if (request.getMessages() != null && !request.getMessages().isEmpty()) {
                messages = new ArrayList<>(request.getMessages());
            } else {
                messages = new ArrayList<>();
                messages.add(Map.of("role", "system", "content", systemPrompt));
                messages.add(Map.of("role", "user", "content", request.getPrompt()));
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("messages", messages);
            body.put("temperature", temperature);
            body.put("max_tokens", maxTokens);

            // Tool calling
            if (request.getTools() != null && !request.getTools().isEmpty()) {
                body.put("tools", request.getTools());
                body.put("tool_choice", request.getToolChoice() != null ? request.getToolChoice() : "auto");
            }

            var httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .timeout(requestTimeout)
                    .build();

            var httpResp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
            int statusCode = httpResp.statusCode();
            String respBody = httpResp.body();

            if (statusCode != 200) {
                String errorDetail = extractError(respBody, statusCode);
                long elapsed = System.currentTimeMillis() - start;
                log.error("[LLM] <<< {} HTTP {}  traceId={}  latency={}ms  error={}",
                        name, statusCode, request.getTraceId(), elapsed, errorDetail);
                throw new RuntimeException(String.format(
                        "%s API returned HTTP %d: %s", name, statusCode, errorDetail));
            }

            var tree = json.readTree(respBody);
            JsonNode choices = tree.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                long elapsed = System.currentTimeMillis() - start;
                log.error("[LLM] <<< {} empty choices  traceId={}  latency={}ms",
                        name, request.getTraceId(), elapsed);
                throw new RuntimeException(
                        name + " returned empty choices — check API key and model availability");
            }

            JsonNode message = choices.get(0).get("message");
            JsonNode usage = tree.get("usage");
            int in = usage != null ? usage.path("prompt_tokens").asInt(0) : 0;
            int out = usage != null ? usage.path("completion_tokens").asInt(0) : 0;
            long elapsed = System.currentTimeMillis() - start;

            // 检查 tool_calls
            JsonNode toolCallsNode = message.get("tool_calls");
            if (toolCallsNode != null && toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                List<ToolCall> toolCalls = parseToolCalls(toolCallsNode);
                log.info("[LLM] <<< {} tool_calls  traceId={}  latency={}ms  count={}",
                        name, request.getTraceId(), elapsed, toolCalls.size());
                return LlmResponse.ofToolCalls(toolCalls, name, model,
                        TokenUsage.of(in, out), elapsed);
            }

            // 纯文本回复
            String content = message.get("content") != null ? message.get("content").asText() : null;

            log.info("[LLM] <<< {} done  traceId={}  latency={}ms  tokens={}/{}  contentLen={}",
                    name, request.getTraceId(), elapsed, in, out,
                    content != null ? content.length() : 0);
            log.debug("[LLM] <<< {} content  traceId={}\n{}",
                    name, request.getTraceId(), content);

            return LlmResponse.of(content, name, model,
                    TokenUsage.of(in, out), elapsed);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[LLM] <<< {} FAILED  traceId={}  latency={}ms  error={}",
                    name, request.getTraceId(), elapsed, e.getMessage());
            throw new RuntimeException(name + " call failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        List<ToolCall> result = new ArrayList<>();
        for (JsonNode tc : toolCallsNode) {
            String id = tc.path("id").asText();
            JsonNode func = tc.get("function");
            if (func == null) continue;
            String name = func.path("name").asText();
            Map<String, Object> arguments = Collections.emptyMap();
            try {
                String argsStr = func.path("arguments").asText();
                if (argsStr != null && !argsStr.isBlank()) {
                    arguments = json.readValue(argsStr, Map.class);
                }
            } catch (Exception e) {
                log.warn("[LLM] Failed to parse tool arguments: {}", e.getMessage());
            }
            result.add(new ToolCall(id, name, arguments));
        }
        return result;
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
