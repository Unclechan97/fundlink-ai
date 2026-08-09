package com.fundlink.ai.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LLM 网关实现 — SmartRouter + Fallback chain + 审计
 */
@Slf4j
@Service
public class LlmGatewayImpl implements LlmGateway {

    private final Map<String, LlmProvider> providers;
    private final AuditPersistenceService auditService;
    private final SmartRouter smartRouter;
    private final List<String> fallbackChain;
    private final int retryMax;

    public LlmGatewayImpl(
            Map<String, LlmProvider> providers,
            AuditPersistenceService auditService,
            SmartRouter smartRouter,
            @Value("${fundlink.llm.router.fallback-chain:deepseek,claude,qwen}") List<String> fallbackChain,
            @Value("${fundlink.llm.router.retry-max:2}") int retryMax) {
        this.providers = providers;
        this.auditService = auditService;
        this.smartRouter = smartRouter;
        this.fallbackChain = fallbackChain;
        this.retryMax = retryMax;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        // Build ordered provider chain (dedup, skip missing)
        List<String> chain = buildChain(request);

        String compactPrompt = compact(request.getPrompt());
        log.info("[GATEWAY] >>> REQ  traceId={}  taskType={}  chain={}  promptLen={}",
                request.getTraceId(), request.getTaskType(), chain,
                request.getPrompt() != null ? request.getPrompt().length() : 0);
        log.info("[GATEWAY] >>> PROMPT  {}", compactPrompt);

        RuntimeException lastError = null;
        for (String providerName : chain) {
            LlmProvider provider = providers.get(providerName);
            if (provider == null) {
                log.warn("[GATEWAY] Provider not registered, skipping: {}  available={}",
                        providerName, providers.keySet());
                continue;
            }

            // Use request model or provider default (null = provider picks default)
            String model = request.getModel();
            long startTime = System.currentTimeMillis();
            boolean success = true;
            String errorMsg = null;
            LlmResponse response = null;

            try {
                response = provider.chat(request);
                String content = response.getContent();
                log.info("[GATEWAY] <<< RESP  traceId={}  provider={}  contentLen={}  tokensIn={}  tokensOut={}",
                        request.getTraceId(), providerName,
                        content != null ? content.length() : 0,
                        response.getTokenUsage().getInputTokens(),
                        response.getTokenUsage().getOutputTokens());
                log.info("[GATEWAY] <<< CONTENT  {}", compact(content));
                return response;
            } catch (Exception e) {
                success = false;
                errorMsg = e.getMessage();
                lastError = new RuntimeException(
                        "Provider " + providerName + " failed: " + e.getMessage(), e);
                log.warn("[GATEWAY] Provider {} failed  traceId={}  error={}",
                        providerName, request.getTraceId(), e.getMessage());
            } finally {
                long latency = System.currentTimeMillis() - startTime;
                auditService.record(request, response, providerName, latency, success, errorMsg);
            }
        }

        log.error("");
        log.error("╔══════════════════════════════════════════════╗");
        log.error("║  [GATEWAY] ALL PROVIDERS FAILED              ║");
        log.error("║  traceId: {}                       ║", request.getTraceId());
        log.error("║  chain tried: {}                         ║", String.join(" → ", chain));
        log.error("║  available: {}                            ║", providers.keySet());
        log.error("║  last error: {}                           ║", lastError != null ? lastError.getMessage() : "null");
        log.error("╚══════════════════════════════════════════════╝");
        log.error("");
        throw new RuntimeException(
                "All LLM providers failed: " + chain, lastError);
    }

    /** Build ordered provider list: explicit provider → SmartRouter → fallback chain */
    private List<String> buildChain(LlmRequest request) {
        Set<String> chain = new LinkedHashSet<>();

        // 1. Explicit provider in request
        if (request.getProvider() != null && !request.getProvider().isBlank()) {
            chain.add(request.getProvider().toLowerCase());
        }

        // 2. SmartRouter selection based on taskType
        String taskType = request.getTaskType();
        if (taskType != null && !taskType.isBlank()) {
            SmartRouter.ModelSelection sel = smartRouter.select(taskType);
            chain.add(sel.provider());
        }

        // 3. Configured fallback chain
        chain.addAll(fallbackChain);

        return new ArrayList<>(chain);
    }

    /** 压缩文本用于日志输出：去掉换行和多余空格 */
    private static String compact(String s) {
        if (s == null) return "null";
        return s.replace("\r", "").replace("\n", "\\n").replaceAll("\\s{2,}", " ");
    }
}
