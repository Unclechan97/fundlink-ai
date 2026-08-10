package com.fundlink.ai.agent.intent;

import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 意图路由器。
 *
 * 两级识别：
 * 1. 快速规则（强特征匹配）— 命中直接返回，0 Token 消耗
 * 2. LLM 意图识别（兜底）— 规则覆盖不到时调用
 *
 * 优先级：TROUBLESHOOTING > INTERFACE_DEV > KNOWLEDGE_QA
 */
@Slf4j
public class IntentRouter {

    private final LlmGateway llmGateway;
    private final ObjectMapper json = new ObjectMapper();

    /** 快速规则置信度阈值 */
    private static final double RULE_CONFIDENCE = 0.95;
    /** LLM 低置信度阈值 — 低于此值触发前端确认 */
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.7;

    public IntentRouter(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    /**
     * 路由用户输入 → 意图结果。
     */
    public IntentResult route(String userInput) {
        return route(userInput, null);
    }

    /**
     * 路由用户输入 → 意图结果（带上下文，如 traceId）。
     */
    public IntentResult route(String userInput, Map<String, Object> context) {
        if (userInput == null || userInput.isBlank()) {
            return IntentResult.unknown();
        }

        // Step 1: 快速规则
        IntentType quick = quickRuleCheck(userInput);
        if (quick != null && quick != IntentType.UNKNOWN) {
            log.info("[Intent] Quick rule hit: {}", quick);
            return IntentResult.of(quick, RULE_CONFIDENCE, "快速规则匹配");
        }

        // Step 2: LLM 兜底
        if (llmGateway != null) {
            return llmIntentRecognition(userInput, context);
        }

        // 无 LLM Gateway → UNKNOWN
        log.info("[Intent] No rule hit, no LLM gateway → UNKNOWN");
        return IntentResult.unknown();
    }

    // ── 快速规则 ──

    /**
     * 强特征快速规则。
     * 返回 null 表示规则未命中，需要走 LLM。
     */
    IntentType quickRuleCheck(String input) {
        // === TROUBLESHOOTING：堆栈跟踪特征（最高优先级）===
        if (input.contains("Exception")
                || input.contains("at com.")
                || input.contains("Caused by:")
                || input.contains("Stack trace:")) {
            return IntentType.TROUBLESHOOTING;
        }

        // === INTERFACE_DEV：HTTP 方法特征 ===
        if (input.matches("(?s).*(?:POST|GET|PUT|DELETE|PATCH)\\s+/[a-zA-Z].*")) {
            return IntentType.INTERFACE_DEV;
        }

        // === INTERFACE_DEV：参数表格特征 ===
        if (input.contains("请求参数") || input.contains("响应参数")
                || input.contains("接口名称") || input.contains("字段名")
                || input.contains("入参") || input.contains("出参")) {
            return IntentType.INTERFACE_DEV;
        }

        // === KNOWLEDGE_QA：疑问句特征 ===
        if (input.contains("?") || input.contains("？")
                || input.startsWith("什么是") || input.startsWith("如何")
                || input.contains("怎么")) {
            return IntentType.KNOWLEDGE_QA;
        }

        return null; // 无强特征
    }

    // ── LLM 识别 ──

    private IntentResult llmIntentRecognition(String userInput, Map<String, Object> context) {
        String traceId = context != null ? (String) context.get("traceId") : null;
        String prompt = buildIntentPrompt(userInput);

        try {
            LlmResponse resp = llmGateway.chat(
                    LlmRequest.ofTask("intent", prompt, traceId));

            IntentResult result = parseIntentResponse(resp.getContent());

            // 低置信度 → 标记需要用户确认
            if (result.getConfidence() < LOW_CONFIDENCE_THRESHOLD) {
                result.setNeedUserConfirm(true);
            }

            return result;
        } catch (Exception e) {
            log.warn("[Intent] LLM recognition failed: {}", e.getMessage());
            return IntentResult.unknown();
        }
    }

    private String buildIntentPrompt(String userInput) {
        return """
                分析用户输入，判断意图类型：
                {
                  "intent": "INTERFACE_DEV|KNOWLEDGE_QA|TROUBLESHOOTING",
                  "confidence": 0.0-1.0,
                  "reason": "简短理由"
                }

                判断规则：
                - INTERFACE_DEV: 包含 API 端点/接口字段/入参出参/接口规范
                - KNOWLEDGE_QA: 询问业务知识/产品规则/流程说明
                - TROUBLESHOOTING: 包含错误日志/异常堆栈/报错描述

                用户输入:
                """ + userInput;
    }

    @SuppressWarnings("unchecked")
    private IntentResult parseIntentResponse(String content) {
        try {
            Map<String, Object> map = json.readValue(content, Map.class);
            String intentStr = (String) map.getOrDefault("intent", "UNKNOWN");
            double confidence = map.get("confidence") instanceof Number
                    ? ((Number) map.get("confidence")).doubleValue() : 0.5;
            String reason = (String) map.getOrDefault("reason", "");

            IntentType type;
            try {
                type = IntentType.valueOf(intentStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                type = IntentType.UNKNOWN;
            }

            return IntentResult.of(type, confidence, reason);
        } catch (Exception e) {
            log.warn("[Intent] Failed to parse LLM response: {}", e.getMessage());
            return IntentResult.unknown();
        }
    }
}
