package com.fundlink.ai.gateway;

import com.fundlink.ai.entity.AiLlmAudit;
import com.fundlink.ai.mapper.AiLlmAuditMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * LLM 网关实现 — 路由 + 审计 + 降级
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGatewayImpl implements LlmGateway {

    private final Map<String, LlmProvider> providers;
    private final AiLlmAuditMapper auditMapper;
    private final PiiRedactor piiRedactor;

    @Override
    public LlmResponse chat(LlmRequest request) {
        String providerName = request.getProvider() != null
                ? request.getProvider().toLowerCase()
                : "qwen";

        log.info("[GATEWAY] Route request  traceId={}  provider={}  model={}  promptLen={}",
                request.getTraceId(), providerName, request.getModel(),
                request.getPrompt().length());

        LlmProvider provider = providers.get(providerName);
        if (provider == null) {
            log.error("[GATEWAY] Provider not found: {}  available={}", providerName, providers.keySet());
            throw new IllegalArgumentException(
                    "Provider not found: " + providerName +
                    ". Available: " + providers.keySet());
        }

        log.info("[GATEWAY] Provider selected: {}  totalProviders={}", providerName, providers.size());

        long startTime = System.currentTimeMillis();
        boolean success = true;
        String errorMsg = null;
        LlmResponse response = null;

        try {
            response = provider.chat(request);
            String content = response.getContent();
            log.info("[GATEWAY] Response received  traceId={}  contentLen={}  tokensIn={}  tokensOut={}",
                    request.getTraceId(), content.length(),
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
            auditCall(request, response, providerName, latency, success, errorMsg);
        }
    }

    @Async
    void auditCall(LlmRequest request, LlmResponse response,
                   String providerName, long latency, boolean success, String errorMsg) {
        try {
            AiLlmAudit audit = new AiLlmAudit();
            audit.setCallId(UUID.randomUUID().toString());
            audit.setProvider(providerName.toUpperCase());
            audit.setModel(request.getModel());
            audit.setTokenInput(response != null ? response.getTokenUsage().getInputTokens() : 0);
            audit.setTokenOutput(response != null ? response.getTokenUsage().getOutputTokens() : 0);
            audit.setCostAmount(response != null
                    ? estimateCost(providerName, request.getModel(), response.getTokenUsage())
                    : BigDecimal.ZERO);
            audit.setLatencyMs((int) latency);
            audit.setSuccess(success ? 1 : 0);
            audit.setErrorMsg(errorMsg);
            audit.setTraceId(request.getTraceId());
            audit.setCreateTime(LocalDateTime.now());
            auditMapper.insert(audit);
        } catch (Exception e) {
            log.error("Failed to write audit log", e);
        }
    }

    private BigDecimal estimateCost(String provider, String model, TokenUsage usage) {
        // 简化版成本估算 (USD per 1K tokens)
        double inputPrice = 0.00015;   // DeepSeek 默认
        double outputPrice = 0.0006;

        double cost = (usage.getInputTokens() / 1000.0 * inputPrice)
                    + (usage.getOutputTokens() / 1000.0 * outputPrice);

        return BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP);
    }
}
