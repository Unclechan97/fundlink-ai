package com.fundlink.ai.agent.intent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD Phase 2: IntentRouter — 快速规则 + LLM 兜底
 *
 * 纯逻辑测试，不依赖 LLM 调用。
 */
@DisplayName("IntentRouter")
class IntentRouterTest {

    private IntentRouter router;

    @BeforeEach
    void setUp() {
        // 不传 LlmGateway → 仅测试快速规则（LLM 兜底返回 UNKNOWN）
        router = new IntentRouter(null);
    }

    // ═══════════════════════════════════════════════════════════
    // 快速规则：INTERFACE_DEV
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("快速规则 → INTERFACE_DEV")
    class InterfaceDevRules {

        @Test
        @DisplayName("包含 POST /api 路径 → 接口开发")
        void shouldDetectHttpMethod() {
            String input = "POST /api/loan/apply 放款申请接口";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.INTERFACE_DEV);
            assertThat(result.getConfidence()).isGreaterThanOrEqualTo(0.9);
            assertThat(result.getReason()).contains("规则");
        }

        @Test
        @DisplayName("包含 GET 路径 → 接口开发")
        void shouldDetectGetMethod() {
            String input = "GET /api/loan/query 查询放款";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.INTERFACE_DEV);
        }

        @Test
        @DisplayName("包含 PUT 路径 → 接口开发")
        void shouldDetectPutMethod() {
            String input = "PUT /api/loan/update 更新贷款";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.INTERFACE_DEV);
        }

        @Test
        @DisplayName("包含「请求参数」→ 接口开发")
        void shouldDetectRequestParams() {
            String input = """
                    接口名称: 放款申请
                    请求参数:
                      - loanNo: 贷款编号
                    """;
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.INTERFACE_DEV);
        }

        @Test
        @DisplayName("包含「响应参数」→ 接口开发")
        void shouldDetectResponseParams() {
            String input = "响应参数: code, message, data";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.INTERFACE_DEV);
        }

        @Test
        @DisplayName("包含「字段名」→ 接口开发")
        void shouldDetectFieldName() {
            String input = """
                    字段名    类型    必填    说明
                    loanNo    String  是     贷款编号
                    """;
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.INTERFACE_DEV);
        }

        @Test
        @DisplayName("包含「入参」「出参」→ 接口开发")
        void shouldDetectInputOutputParams() {
            String input = "入参: loanNo, amount\n出参: code, message";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.INTERFACE_DEV);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 快速规则：TROUBLESHOOTING
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("快速规则 → TROUBLESHOOTING")
    class TroubleshootingRules {

        @Test
        @DisplayName("包含 Exception → 问题排查")
        void shouldDetectException() {
            String input = "java.lang.NullPointerException at com.fundlink.service";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.TROUBLESHOOTING);
        }

        @Test
        @DisplayName("包含 at com. 堆栈 → 问题排查")
        void shouldDetectStackTrace() {
            String input = """
                    错误信息:
                    at com.fundlink.controller.CopilotController.analyze(CopilotController.java:32)
                    at java.base/java.lang.reflect.Method.invoke(Method.java:568)
                    """;
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.TROUBLESHOOTING);
        }

        @Test
        @DisplayName("包含 Caused by: → 问题排查")
        void shouldDetectCausedBy() {
            String input = "Caused by: java.net.ConnectException: Connection refused";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.TROUBLESHOOTING);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 快速规则：KNOWLEDGE_QA
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("快速规则 → KNOWLEDGE_QA")
    class KnowledgeQaRules {

        @Test
        @DisplayName("包含 ? → 知识问答")
        void shouldDetectQuestionMark() {
            String input = "放款流程中什么是T+1对账？";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.KNOWLEDGE_QA);
        }

        @Test
        @DisplayName("以「什么是」开头 → 知识问答")
        void shouldDetectWhatIs() {
            String input = "什么是资金方接入流程？";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.KNOWLEDGE_QA);
        }

        @Test
        @DisplayName("以「如何」开头 → 知识问答")
        void shouldDetectHowTo() {
            String input = "如何配置资金方回调地址？";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.KNOWLEDGE_QA);
        }

        @Test
        @DisplayName("包含「怎么」→ 知识问答")
        void shouldDetectZenMe() {
            String input = "怎么处理资金方返回的异常状态码？";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.KNOWLEDGE_QA);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 优先级测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("规则优先级")
    class RulePriority {

        @Test
        @DisplayName("TROUBLESHOOTING 优先于 INTERFACE_DEV")
        void shouldPrioritizeTroubleshooting() {
            String input = "POST /api/loan/apply\nException in thread...";
            IntentResult result = router.route(input);
            // 异常特征优先于 HTTP 方法特征
            assertThat(result.getIntentType()).isEqualTo(IntentType.TROUBLESHOOTING);
        }

        @Test
        @DisplayName("INTERFACE_DEV 优先于 KNOWLEDGE_QA")
        void shouldPrioritizeInterfaceDev() {
            String input = "POST /api/loan/apply\n这是怎么用的？";
            IntentResult result = router.route(input);
            // HTTP 方法特征优先于疑问句
            assertThat(result.getIntentType()).isEqualTo(IntentType.INTERFACE_DEV);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // LLM 兜底
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LLM 兜底（无 LlmGateway 时）")
    class LlmFallback {

        @Test
        @DisplayName("无规则命中 + 无 LLM Gateway → UNKNOWN")
        void shouldReturnUnknown() {
            String input = "今天天气不错";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.UNKNOWN);
        }

        @Test
        @DisplayName("模糊内容 + 无 LLM → needUserConfirm")
        void shouldNeedUserConfirm() {
            String input = "帮我看看这个文档";
            IntentResult result = router.route(input);
            assertThat(result.getIntentType()).isEqualTo(IntentType.UNKNOWN);
            assertThat(result.isNeedUserConfirm()).isTrue();
        }
    }
}
