package com.fundlink.ai.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 成本优化路由 — 按 taskType 分配 provider + model，全部配置驱动。
 */
@Slf4j
@Component
public class SmartRouter {

    private final String defaultProvider;
    private final String defaultModel;
    private final Map<String, ProviderModel> taskRouting;

    public SmartRouter(
            @Value("${fundlink.llm.router.default-provider:siliconflow}") String defaultProvider,
            @Value("${fundlink.llm.router.default-model:Qwen/Qwen3-8B}") String defaultModel,
            TaskRoutingProperties taskRoutingProperties) {
        this.defaultProvider = defaultProvider;
        this.defaultModel = defaultModel;
        this.taskRouting = taskRoutingProperties.getTaskRouting();
        log.info("[ROUTER] default={}/{}  routes={}", defaultProvider, defaultModel,
                taskRouting != null ? taskRouting.keySet() : "[]");
    }

    /**
     * 根据任务类型选择最优模型。
     *
     * @param taskType simple / requirement / testgen / diagnosis / complex
     * @return {provider, model} 路由结果
     */
    public ModelSelection select(String taskType) {
        ProviderModel pm = taskRouting != null && taskType != null
                ? taskRouting.get(taskType)
                : null;
        if (pm != null) {
            return new ModelSelection(pm.getProvider(), pm.getModel(), 0, 0);
        }
        return new ModelSelection(defaultProvider, defaultModel, 0, 0);
    }

    /** 模型选择结果 */
    public record ModelSelection(String provider, String model,
                                 double costPer1kInput, double costPer1kOutput) {}

    /** 估算成本 (USD) */
    public double estimateCost(ModelSelection sel, int inputTokens, int outputTokens) {
        return (inputTokens / 1000.0 * sel.costPer1kInput())
             + (outputTokens / 1000.0 * sel.costPer1kOutput());
    }
}
