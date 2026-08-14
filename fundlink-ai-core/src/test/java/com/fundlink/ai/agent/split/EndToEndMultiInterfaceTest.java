package com.fundlink.ai.agent.split;

import com.fundlink.ai.agent.PromptBuilder;
import com.fundlink.ai.agent.loop.MultiInterfaceOrchestrator;
import com.fundlink.ai.agent.requirement.MultiInterfaceResult;
import com.fundlink.ai.agent.requirement.ProviderConfig;
import com.fundlink.ai.agent.requirement.RequirementAgent;
import com.fundlink.ai.agent.requirement.RequirementResult;
import com.fundlink.ai.agent.diagnosis.DiagnosisResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端测试：用真实多接口文档验证 拆分 → 过滤 → 并行处理 全链路。
 * 精确复制 CopilotController.analyze() 的逻辑。
 */
@DisplayName("E2E: 拆分 → 并行处理")
class EndToEndMultiInterfaceTest {

    private DocumentSplitter splitter;
    private MultiInterfaceOrchestrator orchestrator;
    private AtomicInteger analyzeCallCount;

    // 与 test-multi-interface.md 内容一致
    private static final String MULTI_DOC = """
            # 星展银行资金接口文档 v2.3

            > 资金方编码: DBS
            > 基础URL: https://api.dbs.com/fund/v2

            ---

            ## 接口 1：放款申请

            ### 基本信息
            - 接口名称: 放款申请
            - 请求地址: POST /api/loan/apply

            ### 请求参数
            | 字段名 | 类型 | 必填 | 说明 |
            |--------|------|------|------|
            | loanNo | String | 是 | 贷款编号 |
            | amount | BigDecimal | 是 | 贷款金额 |

            ---

            ## 接口 2：放款查询

            ### 基本信息
            - 接口名称: 放款查询
            - 请求地址: POST /api/loan/query

            ### 请求参数
            | 字段名 | 类型 | 必填 | 说明 |
            |--------|------|------|------|
            | loanNo | String | 是 | 贷款编号 |

            ---

            ## 接口 3：还款申请

            ### 基本信息
            - 接口名称: 还款申请
            - 请求地址: POST /api/repay/apply

            ### 请求参数
            | 字段名 | 类型 | 必填 | 说明 |
            |--------|------|------|------|
            | loanNo | String | 是 | 贷款编号 |
            | repayAmount | BigDecimal | 是 | 还款金额 |

            ---

            ## 接口 4：还款查询

            ### 基本信息
            - 接口名称: 还款查询
            - 请求地址: POST /api/repay/query

            ### 请求参数
            | 字段名 | 类型 | 必填 | 说明 |
            |--------|------|------|------|
            | loanNo | String | 是 | 贷款编号 |
            """;

    @BeforeEach
    void setUp() {
        splitter = new DocumentSplitter(new InterfaceDeduplicator());
        analyzeCallCount = new AtomicInteger(0);

        // Stub agent: 每次调用计数 +1
        RequirementAgent stubAgent = (documentText, providerCode, flowType, previousErrors) -> {
            analyzeCallCount.incrementAndGet();
            RequirementResult r = new RequirementResult();
            r.setProviderConfig(new ProviderConfig());
            r.setFlowType(flowType);
            return r;
        };

        Executor syncExecutor = Runnable::run;
        orchestrator = new MultiInterfaceOrchestrator(
                stubAgent, null, new PromptBuilder(), syncExecutor);
    }

    // ═══════════════════════════════════════════════
    // 测试 1: 拆分数量
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("拆分多接口文档 → 4 个 segment")
    void shouldSplitIntoFour() {
        List<InterfaceSegment> segments = splitter.split(MULTI_DOC);

        assertThat(segments).hasSize(4);
        assertThat(segments.get(0).getInterfaceName()).contains("放款申请");
        assertThat(segments.get(1).getInterfaceName()).contains("放款查询");
        assertThat(segments.get(2).getInterfaceName()).contains("还款申请");
        assertThat(segments.get(3).getInterfaceName()).contains("还款查询");
    }

    // ═══════════════════════════════════════════════
    // 测试 2: ID 一致性（两次拆分产生相同 ID）
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("同一文档拆分两次 → 相同 interfaceId")
    void shouldProduceSameIdsOnReSplit() {
        List<InterfaceSegment> split1 = splitter.split(MULTI_DOC);
        List<InterfaceSegment> split2 = splitter.split(MULTI_DOC);

        assertThat(split1).hasSize(split2.size());
        for (int i = 0; i < split1.size(); i++) {
            assertThat(split1.get(i).getInterfaceId())
                    .isEqualTo(split2.get(i).getInterfaceId());
        }
    }

    // ═══════════════════════════════════════════════
    // 测试 3: 全选 → 并行处理 4 个
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("全选 4 个接口 → 4 次 analyze 调用（模拟真实 LLM 返回）")
    void shouldProcessAllSelected() {
        // Step 1 + 2 + 3 same as before
        List<InterfaceSegment> allSegments = splitter.split(MULTI_DOC);
        List<String> selectedIds = allSegments.stream()
                .map(InterfaceSegment::getInterfaceId).toList();
        List<InterfaceSegment> selected = allSegments.stream()
                .filter(s -> selectedIds.contains(s.getInterfaceId()))
                .toList();
        assertThat(selected).hasSize(4);

        // Step 4: 并行处理
        MultiInterfaceResult result = orchestrator.processInterfaces(selected, "DBS", "LOAN");

        assertThat(result.getTotalCount()).isEqualTo(4);
        assertThat(result.getSuccessCount()).isEqualTo(4);
        assertThat(result.getFailedCount()).isEqualTo(0);
        assertThat(analyzeCallCount.get()).isEqualTo(4);
        // 验证每个 item 的 result 非空
        result.getInterfaces().forEach(item -> {
            assertThat(item.getResult()).isNotNull();
            assertThat(item.getStatus()).isEqualTo("SUCCESS");
        });
    }

    @Test
    @DisplayName("部分失败 → successCount + failedCount 正确")
    void shouldHandlePartialFailures() {
        // 重建 orchestrator：第 3 个接口返回 parseError
        AtomicInteger callCount = new AtomicInteger(0);
        RequirementAgent flakyAgent = (documentText, providerCode, flowType, previousErrors) -> {
            int n = callCount.incrementAndGet();
            RequirementResult r = new RequirementResult();
            r.setProviderConfig(new ProviderConfig());
            r.setFlowType(flowType);
            if (n == 3) { r.setParseError("Simulated failure"); }
            return r;
        };

        Executor syncExecutor = Runnable::run;
        MultiInterfaceOrchestrator flakyOrch = new MultiInterfaceOrchestrator(
                flakyAgent, null, new PromptBuilder(), syncExecutor);

        List<InterfaceSegment> allSegments = splitter.split(MULTI_DOC);
        List<String> selectedIds = allSegments.stream()
                .map(InterfaceSegment::getInterfaceId).toList();
        List<InterfaceSegment> selected = allSegments.stream()
                .filter(s -> selectedIds.contains(s.getInterfaceId()))
                .toList();

        MultiInterfaceResult result = flakyOrch.processInterfaces(selected, "DBS", "LOAN");

        assertThat(result.getTotalCount()).isEqualTo(4);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailedCount()).isEqualTo(1);
    }

    // ═══════════════════════════════════════════════
    // 测试 4: 部分选中
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("只选 2 个 → 2 次 analyze 调用")
    void shouldProcessOnlySelected() {
        List<InterfaceSegment> allSegments = splitter.split(MULTI_DOC);

        // 只选前 2 个
        List<String> selectedIds = allSegments.stream()
                .limit(2).map(InterfaceSegment::getInterfaceId).toList();

        List<InterfaceSegment> selected = allSegments.stream()
                .filter(s -> selectedIds.contains(s.getInterfaceId()))
                .toList();

        assertThat(selected).hasSize(2);

        MultiInterfaceResult result = orchestrator.processInterfaces(
                selected, "DBS", "LOAN");

        assertThat(result.getTotalCount()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(analyzeCallCount.get()).isEqualTo(2);
    }

    // ═══════════════════════════════════════════════
    // 测试 5: sectionText 只含当前接口
    // ═══════════════════════════════════════════════

    @Test
    @DisplayName("每个 segment 的 sectionText 不包含其他接口内容")
    void shouldIsolateSectionText() {
        List<InterfaceSegment> segments = splitter.split(MULTI_DOC);

        // 接口 1 的 sectionText 不应包含"放款查询"
        assertThat(segments.get(0).getSectionText())
                .doesNotContain("放款查询");

        // 接口 1 的 sectionText 应包含自己的请求参数
        assertThat(segments.get(0).getSectionText())
                .contains("loanNo");
        assertThat(segments.get(0).getSectionText())
                .contains("amount");
    }
}
