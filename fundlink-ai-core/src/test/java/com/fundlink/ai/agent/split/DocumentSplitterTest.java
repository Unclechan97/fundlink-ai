package com.fundlink.ai.agent.split;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD Phase 1: DocumentSplitter + 拆分策略 + 去重
 *
 * 验证 4 级策略链降级、去重逻辑、兜底行为。
 * 纯逻辑测试，不需要 Spring 上下文。
 */
@DisplayName("DocumentSplitter")
class DocumentSplitterTest {

    private DocumentSplitter splitter;

    @BeforeEach
    void setUp() {
        splitter = new DocumentSplitter(new InterfaceDeduplicator());
    }

    // ═══════════════════════════════════════════════════════════
    // Strategy 1: Markdown 标题匹配
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MarkdownHeadingStrategy — 标题匹配")
    class MarkdownHeadingTests {

        @Test
        @DisplayName("3 个 ## 接口标题 → 拆出 3 个")
        void shouldSplitThreeMarkdownHeadings() {
            String doc = """
                    # 某银行接口文档

                    ## 1. 放款申请接口
                    请求地址: POST /api/loan/apply
                    请求参数: loanNo, amount

                    ## 2. 放款查询接口
                    请求地址: POST /api/loan/query
                    请求参数: loanNo

                    ## 3. 还款申请接口
                    请求地址: POST /api/repay/apply
                    请求参数: loanNo, repayAmount
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(3);
            assertThat(segments.get(0).getInterfaceName()).contains("放款申请");
            assertThat(segments.get(1).getInterfaceName()).contains("放款查询");
            assertThat(segments.get(2).getInterfaceName()).contains("还款申请");
            assertThat(segments.get(0).getSplitSource()).isEqualTo(SplitSource.MARKDOWN_HEADING);
            assertThat(segments.get(0).getSplitConfidence()).isGreaterThanOrEqualTo(0.9);
        }

        @Test
        @DisplayName("### 三级标题也匹配")
        void shouldMatchH3Headings() {
            String doc = """
                    ### 放款回调通知
                    请求地址: POST /api/loan/callback
                    用于接收放款结果回调
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(1);
            assertThat(segments.get(0).getInterfaceName()).contains("放款回调通知");
        }

        @Test
        @DisplayName("英文接口标题也匹配")
        void shouldMatchEnglishHeadings() {
            String doc = """
                    ## Loan Apply API
                    POST /api/v2/loan/apply

                    ## Loan Query API
                    POST /api/v2/loan/query
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(2);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Strategy 2: 分隔线匹配
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DelimiterStrategy — 分隔线匹配")
    class DelimiterTests {

        @Test
        @DisplayName("--- 分隔 3 个接口 → 拆出 3 个")
        void shouldSplitByHorizontalRule() {
            String doc = """
                    接口名称: 放款申请
                    POST /api/loan/apply

                    ---

                    接口名称: 放款查询
                    POST /api/loan/query

                    ---

                    接口名称: 还款申请
                    POST /api/repay/apply
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(3);
            assertThat(segments.get(0).getSplitSource()).isEqualTo(SplitSource.DELIMITER);
        }

        @Test
        @DisplayName("*** 分隔符也支持")
        void shouldSplitByAsterisks() {
            String doc = """
                    放款申请接口
                    POST /api/loan/apply

                    ***

                    放款查询接口
                    POST /api/loan/query
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(2);
        }

        @Test
        @DisplayName("中文数字序号分隔")
        void shouldSplitByChineseNumberedSections() {
            String doc = """
                    一、放款申请
                    POST /api/loan/apply

                    二、放款查询
                    POST /api/loan/query

                    三、还款申请
                    POST /api/repay/apply
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(3);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Strategy 3: 端点锚点匹配
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AnchorStrategy — 端点锚点匹配")
    class AnchorTests {

        @Test
        @DisplayName("按接口地址锚点拆分")
        void shouldSplitByEndpointAnchors() {
            String doc = """
                    资金方接口规范

                    接口地址: POST /api/loan/apply
                    功能: 放款申请
                    字段: loanNo, amount

                    接口地址: POST /api/loan/query
                    功能: 放款查询
                    字段: loanNo

                    接口地址: POST /api/repay/apply
                    功能: 还款申请
                    字段: loanNo, repayAmount
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(3);
            assertThat(segments.get(0).getSplitSource()).isEqualTo(SplitSource.ANCHOR);
            assertThat(segments.get(0).getMethod()).isEqualTo("POST");
            assertThat(segments.get(0).getEndpoint()).isEqualTo("/api/loan/apply");
        }

        @Test
        @DisplayName("英文 endpoint 锚点")
        void shouldMatchEnglishEndpointLabels() {
            String doc = """
                    API: POST /api/v2/loan/apply
                    Description: Submit loan application

                    endpoint: GET /api/v2/loan/status
                    Description: Query loan status
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(2);
            assertThat(segments.get(0).getEndpoint()).isEqualTo("/api/v2/loan/apply");
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Strategy 4: 兜底
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("FullDocStrategy — 兜底策略")
    class FallbackTests {

        @Test
        @DisplayName("无结构纯文本 → 拆出 1 个（兜底）")
        void shouldFallbackToFullDoc() {
            String doc = """
                    这是一段没有标题、没有分隔线、
                    也没有明确端点定义的接口描述文字。
                    放款时需要传 loanNo 和 amount 字段。
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(1);
            assertThat(segments.get(0).getSplitSource()).isEqualTo(SplitSource.FULL_DOC);
            assertThat(segments.get(0).getSectionText()).isEqualTo(doc.trim());
        }

        @Test
        @DisplayName("空文档 → 返回空列表")
        void shouldHandleEmptyDoc() {
            List<InterfaceSegment> segments = splitter.split("");

            assertThat(segments).isEmpty();
        }

        @Test
        @DisplayName("仅空白文档 → 返回空列表")
        void shouldHandleBlankDoc() {
            List<InterfaceSegment> segments = splitter.split("   \n  \n  ");

            assertThat(segments).isEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 去重测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("去重 — InterfaceDeduplicator")
    class DeduplicationTests {

        @Test
        @DisplayName("相同 endpoint → 保留内容更长的")
        void shouldDeduplicateSameEndpoint() {
            String doc = """
                    ## 放款申请
                    POST /api/loan/apply
                    字段: loanNo

                    ## 放款申请接口
                    POST /api/loan/apply
                    字段: loanNo, amount, customerId, customerName
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            // 应该去重为 1 个，保留内容更丰富的
            assertThat(segments).hasSize(1);
            assertThat(segments.get(0).getSectionText()).contains("customerName");
        }

        @Test
        @DisplayName("不同 endpoint → 不去重")
        void shouldNotDeduplicateDifferentEndpoints() {
            String doc = """
                    ## 放款申请
                    POST /api/loan/apply

                    ## 放款查询
                    POST /api/loan/query
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(2);
        }

        @Test
        @DisplayName("相同路径、不同 method → 不去重")
        void shouldKeepDifferentMethods() {
            String doc = """
                    ## 查询放款
                    GET /api/loan/123

                    ## 更新放款
                    PUT /api/loan/123
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(2);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 综合测试
    // ═══════════════════════════════════════════════════════════

    @Nested
    @DisplayName("综合场景")
    class IntegrationTests {

        @Test
        @DisplayName("单接口文档 → 拆出 1 个，退化为现有逻辑")
        void shouldHandleSingleInterface() {
            String doc = """
                    ## 放款申请接口
                    请求地址: POST /api/loan/apply
                    请求字段:
                      - loanNo (String, 必填) 贷款编号
                      - amount (BigDecimal, 必填) 贷款金额
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(1);
            assertThat(segments.get(0).getInterfaceName()).contains("放款申请");
        }

        @Test
        @DisplayName("每个 segment 都有 interfaceId")
        void shouldAssignUniqueInterfaceId() {
            String doc = """
                    ## 放款申请
                    POST /api/loan/apply

                    ## 放款查询
                    POST /api/loan/query

                    ## 还款申请
                    POST /api/repay/apply
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments).hasSize(3);
            // 每个 segment 的 interfaceId 非空且唯一
            List<String> ids = segments.stream().map(InterfaceSegment::getInterfaceId).toList();
            assertThat(ids).allMatch(id -> id != null && !id.isBlank());
            assertThat(ids).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("每个 segment 的 index 正确递增")
        void shouldAssignCorrectIndices() {
            String doc = """
                    ## 放款申请
                    POST /api/loan/apply

                    ## 放款查询
                    POST /api/loan/query

                    ## 还款申请
                    POST /api/repay/apply
                    """;

            List<InterfaceSegment> segments = splitter.split(doc);

            assertThat(segments.get(0).getIndex()).isEqualTo(0);
            assertThat(segments.get(1).getIndex()).isEqualTo(1);
            assertThat(segments.get(2).getIndex()).isEqualTo(2);
        }
    }
}
