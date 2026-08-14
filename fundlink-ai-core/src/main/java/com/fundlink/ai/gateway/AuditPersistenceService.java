package com.fundlink.ai.gateway;

import com.fundlink.ai.entity.AiLlmAudit;
import com.fundlink.ai.mapper.AiLlmAuditMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 审计持久化服务 — 独立 Service 以便 @Async 通过 Spring 代理生效
 */
@Slf4j
@Service
public class AuditPersistenceService {

    private final AiLlmAuditMapper auditMapper;
    private final Map<String, BigDecimal> inputPrices;
    private final Map<String, BigDecimal> outputPrices;

    public AuditPersistenceService(
            AiLlmAuditMapper auditMapper,
            @Value("${fundlink.llm.providers.qwen.input-price:0.002}") BigDecimal qwenInputPrice,
            @Value("${fundlink.llm.providers.qwen.output-price:0.008}") BigDecimal qwenOutputPrice,
            @Value("${fundlink.llm.providers.deepseek.input-price:0.00015}") BigDecimal dsInputPrice,
            @Value("${fundlink.llm.providers.deepseek.output-price:0.0006}") BigDecimal dsOutputPrice,
            @Value("${fundlink.llm.providers.siliconflow.input-price:0}") BigDecimal sfInputPrice,
            @Value("${fundlink.llm.providers.siliconflow.output-price:0}") BigDecimal sfOutputPrice) {
        this.auditMapper = auditMapper;
        this.inputPrices = Map.of(
                "qwen", qwenInputPrice,
                "deepseek", dsInputPrice,
                "siliconflow", sfInputPrice
        );
        this.outputPrices = Map.of(
                "qwen", qwenOutputPrice,
                "deepseek", dsOutputPrice,
                "siliconflow", sfOutputPrice
        );
    }

    @Async
    public void record(LlmRequest request, LlmResponse response,
                       String providerName, long latency, boolean success, String errorMsg) {
        try {
            AiLlmAudit audit = new AiLlmAudit();
            audit.setCallId(UUID.randomUUID().toString());
            audit.setProvider(providerName.toUpperCase());
            audit.setModel(response != null && response.getModel() != null
                    ? response.getModel()
                    : (request.getModel() != null ? request.getModel() : "unknown"));
            audit.setTokenInput(response != null ? response.getTokenUsage().getInputTokens() : 0);
            audit.setTokenOutput(response != null ? response.getTokenUsage().getOutputTokens() : 0);
            audit.setCostAmount(response != null
                    ? estimateCost(providerName, response.getTokenUsage())
                    : BigDecimal.ZERO);
            audit.setLatencyMs((int) latency);
            audit.setSuccess(success ? 1 : 0);
            audit.setErrorMsg(errorMsg);
            audit.setTraceId(request.getTraceId());
            audit.setCreateTime(LocalDateTime.now());
            auditMapper.insert(audit);
        } catch (Exception e) {
            log.error("[AUDIT] Failed to write audit log", e);
        }
    }

    private BigDecimal estimateCost(String provider, TokenUsage usage) {
        String key = provider != null ? provider.toLowerCase() : "qwen";
        double inputPrice = inputPrices.getOrDefault(key, BigDecimal.valueOf(0.00015)).doubleValue();
        double outputPrice = outputPrices.getOrDefault(key, BigDecimal.valueOf(0.0006)).doubleValue();

        double cost = (usage.getInputTokens() / 1000.0 * inputPrice)
                    + (usage.getOutputTokens() / 1000.0 * outputPrice);

        return BigDecimal.valueOf(cost).setScale(6, RoundingMode.HALF_UP);
    }
}
