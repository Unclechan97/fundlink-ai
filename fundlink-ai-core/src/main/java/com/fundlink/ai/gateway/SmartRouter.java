package com.fundlink.ai.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 成本优化路由 — 小任务用便宜模型，复杂任务用贵模型
 */
@Slf4j
@Component
public class SmartRouter {

    /**
     * 根据任务类型选择最优模型
     * @return {provider, model} 最佳模型
     */
    public ModelSelection select(String taskType) {
        return switch (taskType != null ? taskType : "default") {
            case "simple", "classification" -> {
                yield new ModelSelection("siliconflow", "Qwen/Qwen3-8B", 0, 0);
            }
            case "requirement", "testgen" -> {
                yield new ModelSelection("siliconflow", "Qwen/Qwen3-8B", 0, 0);
            }
            case "diagnosis", "complex" -> {
                yield new ModelSelection("siliconflow", "Qwen/Qwen3-8B", 0, 0);
            }
            default -> {
                yield new ModelSelection("siliconflow", "Qwen/Qwen3-8B", 0, 0);
            }
        };
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
