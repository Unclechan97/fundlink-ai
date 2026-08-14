package com.fundlink.ai.agent.intent;

import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 意图路由器。
 *
 * 两级识别：
 * 1. 快速规则（强特征匹配）— 命中直接返回，0 Token 消耗
 * 2. LLM 意图识别（兜底）— 规则覆盖不到时调用
 *
 * 优先级：TROUBLESHOOTING > INTERFACE_DEV > KNOWLEDGE_QA
 * <p>
 * 排查 vs 问答的区分原则：故障现场信号强于疑问句式 —
 * "为什么放款失败了？"是排查（事故提问词×故障词组合），
 * 只有定义/操作句式（什么是/如何/怎么）且无故障词时才判问答；
 * 裸"？"不再直接判问答，交给 LLM 兜底。
 */
@Slf4j
public class IntentRouter {

    private final LlmGateway llmGateway;
    private final ObjectMapper json = new ObjectMapper();

    /** 快速规则置信度阈值 */
    private static final double RULE_CONFIDENCE = 0.95;
    /** LLM 低置信度阈值 — 低于此值触发前端确认 */
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.7;

    /** 堆栈/日志特征（小写匹配）— 出现即排查 */
    private static final List<String> TS_LOG_MARKERS = List.of(
            "exception", "at com.", "caused by:", "stack trace:", "error",
            "nullpointerexception", "outofmemoryerror");

    /** 事故提问词 — 与故障词组合出现时判排查（刻意排除"怎么/如何"这类操作问答词） */
    private static final List<String> TS_QUESTION_WORDS = List.of(
            "为什么", "为啥", "为何", "什么原因", "什么情况", "什么问题", "怎么回事",
            "哪里", "哪", "啥");

    /** 故障词 — 与事故提问词组合出现时判排查 */
    private static final List<String> TS_FAILURE_WORDS = List.of(
            "失败", "报错", "错误", "异常", "超时", "拒绝", "连不上", "挂了", "崩", "慢", "卡", "错了");

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
        String lower = input.toLowerCase();

        // === TROUBLESHOOTING：堆栈/日志特征（最高优先级）===
        if (TS_LOG_MARKERS.stream().anyMatch(lower::contains)) {
            return IntentType.TROUBLESHOOTING;
        }

        // === 定义句式优先（"什么是失败重试策略"是问答，不是排查）===
        if (input.startsWith("什么是") || input.startsWith("是什么")) {
            return IntentType.KNOWLEDGE_QA;
        }

        // === TROUBLESHOOTING：单独故障现场词（"模板渲染报错"）===
        if (input.contains("报错")) {
            return IntentType.TROUBLESHOOTING;
        }

        // === TROUBLESHOOTING：事故提问 × 故障词组合 ===
        // "为什么放款失败了？" / "查询接口为什么这么慢" / "这是什么原因导致的超时"
        boolean hasIncidentQuestion = TS_QUESTION_WORDS.stream().anyMatch(input::contains);
        boolean hasFailureWord = TS_FAILURE_WORDS.stream().anyMatch(input::contains);
        if (hasIncidentQuestion && hasFailureWord) {
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

        // === KNOWLEDGE_QA：定义/操作/原因类疑问词（无故障词）===
        if (input.startsWith("如何") || input.startsWith("为什么")
                || input.contains("怎么")) {
            return IntentType.KNOWLEDGE_QA;
        }
        // 疑问句 + 定义词（"T+1对账是什么？" / "流程中什么是T+1对账？"）
        if ((input.contains("?") || input.contains("？"))
                && (input.contains("是什么") || input.contains("什么是"))) {
            return IntentType.KNOWLEDGE_QA;
        }

        // 裸"？"等弱特征不再直接判问答 — 交给 LLM 兜底
        return null;
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
                - TROUBLESHOOTING: 包含错误日志/异常堆栈/报错描述，或针对某个具体故障提问。
                  注意：即使以疑问句形式提问（如"为什么XX失败了/报错了/超时了"），
                  只要指向具体故障，一律归 TROUBLESHOOTING。
                - KNOWLEDGE_QA: 询问概念/产品规则/流程说明/如何配置，无具体故障现场

                示例：
                - "为什么放款接口报500错误？" → TROUBLESHOOTING
                - "什么是资金方接入流程？" → KNOWLEDGE_QA
                - "如何配置回调地址？" → KNOWLEDGE_QA
                - "POST /api/loan/apply 放款申请" → INTERFACE_DEV

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
