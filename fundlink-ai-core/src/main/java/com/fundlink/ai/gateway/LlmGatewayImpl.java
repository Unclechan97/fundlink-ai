package com.fundlink.ai.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * LLM 网关实现 — 路由 + 审计
 */
@Slf4j
@Service
public class LlmGatewayImpl implements LlmGateway {

    private final Map<String, LlmProvider> providers;
    private final AuditPersistenceService auditService;

    public LlmGatewayImpl(Map<String, LlmProvider> providers, AuditPersistenceService auditService) {
        this.providers = providers;
        this.auditService = auditService;
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        String providerName = request.getProvider() != null
                ? request.getProvider().toLowerCase()
                : "qwen";

        log.info("[GATEWAY] Route request  traceId={}  provider={}  model={}  promptLen={}",
                request.getTraceId(), providerName, request.getModel(),
                request.getPrompt() != null ? request.getPrompt().length() : 0);

        LlmProvider provider = providers.get(providerName);
        if (provider == null) {
            log.error("[GATEWAY] Provider not found: {}  available={}", providerName, providers.keySet());
            throw new IllegalArgumentException(
                    "Provider not found: " + providerName +
                    ". Available: " + providers.keySet());
        }

        log.debug("[GATEWAY] Provider selected: {}  totalProviders={}", providerName, providers.size());

        long startTime = System.currentTimeMillis();
        boolean success = true;
        String errorMsg = null;
        LlmResponse response = null;

        try {
            response = provider.chat(request);
            String content = response.getContent();
            log.info("[GATEWAY] Response received  traceId={}  contentLen={}  tokensIn={}  tokensOut={}",
                    request.getTraceId(),
                    content != null ? content.length() : 0,
                    response.getTokenUsage().getInputTokens(),
                    response.getTokenUsage().getOutputTokens());
            log.debug("[GATEWAY] Response content  traceId={}\n{}",
                    request.getTraceId(), content);
            return response;
        } catch (Exception e) {
            success = false;
            errorMsg = e.getMessage();
            log.error("[GATEWAY] Call FAILED  provider={}  traceId={}  error={}",
                    providerName, request.getTraceId(), e.getMessage());
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        } finally {
            long latency = System.currentTimeMillis() - startTime;
            auditService.record(request, response, providerName, latency, success, errorMsg);
        }
    }
}
