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
                // 简单任务 → Qwen
                yield new ModelSelection("qwen", "qwen-plus", 0.002, 0.008);
            }
            case "requirement", "testgen" -> {
                // 配置生成 → Qwen (体验额度)
                yield new ModelSelection("qwen", "qwen-plus", 0.002, 0.008);
            }
            case "diagnosis", "complex" -> {
                // 复杂诊断 → Claude (推理能力强)
                yield new ModelSelection("claude", "claude-haiku-4-5-20251001", 0.001, 0.005);
            }
            default -> {
                yield new ModelSelection("qwen", "qwen-plus", 0.002, 0.008);
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
