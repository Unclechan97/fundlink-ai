package com.fundlink.ai.agent;

import com.fundlink.ai.agent.split.InterfaceSegment;
import com.fundlink.ai.agent.split.SplitSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD Phase 3: PromptBuilder — buildInterfacePrompt()
 *
 * 验证子 Agent 独立 Prompt 生成：只传当前接口片段 + 兄弟接口摘要。
 */
@DisplayName("PromptBuilder — buildInterfacePrompt")
class PromptBuilderTest {

    private PromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new PromptBuilder();
    }

    @Nested
    @DisplayName("单接口 Prompt 生成")
    class InterfacePrompt {

        @Test
        @DisplayName("Prompt 包含当前接口片段")
        void shouldContainCurrentInterfaceSection() {
            InterfaceSegment seg = createSegment(
                    "loanApply_abc123", "放款申请", "POST", "/api/loan/apply",
                    "loanNo, amount, customerId", 0, SplitSource.MARKDOWN_HEADING);

            List<InterfaceSegment> siblings = List.of(
                    createSegment("loanQuery_def456", "放款查询", "POST", "/api/loan/query",
                            "loanNo", 1, SplitSource.MARKDOWN_HEADING)
            );

            String prompt = builder.buildInterfacePrompt(seg, siblings, "LOAN", "TEST_BANK");

            // 应包含当前接口的 sectionText
            assertThat(prompt).contains("loanNo, amount, customerId");
            // 应包含接口名称
            assertThat(prompt).contains("放款申请");
            // 应包含端点
            assertThat(prompt).contains("POST /api/loan/apply");
            // 应包含位置信息
            assertThat(prompt).contains("1/2");
        }

        @Test
        @DisplayName("Prompt 包含兄弟接口摘要（不传全文）")
        void shouldContainSiblingSummary() {
            InterfaceSegment seg = createSegment(
                    "loanApply_abc123", "放款申请", "POST", "/api/loan/apply",
                    "loanNo, amount", 0, SplitSource.MARKDOWN_HEADING);

            List<InterfaceSegment> siblings = List.of(
                    createSegment("loanQuery_def456", "放款查询", "POST", "/api/loan/query",
                            "SIBLING_ONLY_TEXT_xyz", 1, SplitSource.MARKDOWN_HEADING)
            );

            String prompt = builder.buildInterfacePrompt(seg, siblings, "LOAN", "TEST_BANK");

            // 应包含兄弟接口的名称和端点（摘要）
            assertThat(prompt).contains("放款查询");
            assertThat(prompt).contains("/api/loan/query");
            // 但不应包含兄弟接口的详细内容（sectionText）
            assertThat(prompt).doesNotContain("SIBLING_ONLY_TEXT_xyz");
        }

        @Test
        @DisplayName("无兄弟接口时不报错")
        void shouldHandleNoSiblings() {
            InterfaceSegment seg = createSegment(
                    "fullDoc_xxx", "接口文档", "", "",
                    "some content", 0, SplitSource.FULL_DOC);

            String prompt = builder.buildInterfacePrompt(seg, null, "LOAN", "TEST_BANK");

            assertThat(prompt).isNotNull();
            assertThat(prompt).contains("接口文档");
        }

        @Test
        @DisplayName("Prompt 包含 flowType 和 providerCode")
        void shouldContainContextInfo() {
            InterfaceSegment seg = createSegment(
                    "repayApply_ghi789", "还款申请", "POST", "/api/repay/apply",
                    "repayAmount", 0, SplitSource.ANCHOR);

            String prompt = builder.buildInterfacePrompt(seg, List.of(), "REPAY", "CMB");

            assertThat(prompt).contains("CMB"); // providerCode
            assertThat(prompt).contains("REPAY"); // flowType
        }
    }

    @Nested
    @DisplayName("全局上下文注入")
    class GlobalContext {

        @Test
        @DisplayName("注入 GlobalContext 公共字段")
        void shouldInjectCommonFields() {
            InterfaceSegment seg = createSegment(
                    "loanApply_abc", "放款申请", "POST", "/api/loan/apply",
                    "sign, timestamp, loanNo", 0, SplitSource.MARKDOWN_HEADING);

            String prompt = builder.buildInterfacePrompt(
                    seg, List.of(), "LOAN", "TEST_BANK");

            // 应包含数据源字段目录（从 field-catalog.yml 加载）
            assertThat(prompt).contains("数据源");
        }
    }

    // ── helper ──

    private InterfaceSegment createSegment(String id, String name, String method,
                                           String endpoint, String sectionText,
                                           int index, SplitSource source) {
        InterfaceSegment seg = new InterfaceSegment();
        seg.setInterfaceId(id);
        seg.setInterfaceName(name);
        seg.setMethod(method);
        seg.setEndpoint(endpoint);
        seg.setSectionText(sectionText);
        seg.setIndex(index);
        seg.setSplitSource(source);
        seg.setSplitConfidence(0.95);
        return seg;
    }
}
