package com.fundlink.ai.agent.loop;

import com.fundlink.ai.agent.PromptBuilder;
import com.fundlink.ai.agent.requirement.*;
import com.fundlink.ai.agent.split.InterfaceSegment;
import com.fundlink.ai.agent.split.SplitSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD Phase 3b: MultiInterfaceOrchestrator — 并行接口处理
 */
@DisplayName("MultiInterfaceOrchestrator")
class MultiInterfaceOrchestratorTest {

    private MultiInterfaceOrchestrator orch;
    private StubRequirementAgent stubAgent;

    /** 同步 executor — 并行变串行，方便断言 */
    private final Executor syncExecutor = Runnable::run;

    @BeforeEach
    void setUp() {
        stubAgent = new StubRequirementAgent();
        orch = new MultiInterfaceOrchestrator(
                stubAgent, null, new PromptBuilder(), syncExecutor);
    }

    @Nested
    @DisplayName("单接口 — 退化")
    class SingleInterface {

        @Test
        @DisplayName("1 个 segment → 1 SUCCESS")
        void shouldProcessSingleSegment() {
            List<InterfaceSegment> segments = List.of(
                    createSegment("loanApply", "放款申请", "POST", "/api/loan/apply",
                            "loanNo, amount", 0)
            );

            MultiInterfaceResult result = orch.processInterfaces(
                    segments, "TEST_BANK", "LOAN");

            assertThat(result.getTotalCount()).isEqualTo(1);
            assertThat(result.getSuccessCount()).isEqualTo(1);
            assertThat(result.getFailedCount()).isEqualTo(0);
            assertThat(result.getInterfaces().get(0).getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getInterfaces().get(0).getInterfaceName()).isEqualTo("放款申请");
        }
    }

    @Nested
    @DisplayName("多接口并行")
    class MultiInterface {

        @Test
        @DisplayName("3 个 segment → 3 SUCCESS")
        void shouldProcessMultipleSegments() {
            List<InterfaceSegment> segments = List.of(
                    createSegment("loanApply", "放款申请", "POST", "/api/loan/apply", "a", 0),
                    createSegment("loanQuery", "放款查询", "POST", "/api/loan/query", "b", 1),
                    createSegment("repayApply", "还款申请", "POST", "/api/repay/apply", "c", 2)
            );

            MultiInterfaceResult result = orch.processInterfaces(
                    segments, "TEST_BANK", "LOAN");

            assertThat(result.getTotalCount()).isEqualTo(3);
            assertThat(result.getSuccessCount()).isEqualTo(3);
            assertThat(result.getFailedCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("每个 Result 携带 interfaceId + index")
        void shouldSetInterfaceMetadata() {
            List<InterfaceSegment> segments = List.of(
                    createSegment("loanApply_abc", "放款申请", "POST", "/api/loan/apply", "a", 0),
                    createSegment("loanQuery_def", "放款查询", "POST", "/api/loan/query", "b", 1)
            );

            MultiInterfaceResult result = orch.processInterfaces(
                    segments, "TEST_BANK", "LOAN");

            RequirementResult r0 = result.getInterfaces().get(0).getResult();
            assertThat(r0.getInterfaceId()).isEqualTo("loanApply_abc");
            assertThat(r0.getInterfaceIndex()).isEqualTo(0);
            assertThat(r0.getTotalInterfaces()).isEqualTo(2);

            RequirementResult r1 = result.getInterfaces().get(1).getResult();
            assertThat(r1.getInterfaceId()).isEqualTo("loanQuery_def");
            assertThat(r1.getInterfaceIndex()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("错误隔离")
    class ErrorIsolation {

        @Test
        @DisplayName("1 个失败 → 其他仍 SUCCESS")
        void shouldIsolateFailures() {
            // stubAgent will fail for segment with "FAIL" in interfaceId
            List<InterfaceSegment> segments = List.of(
                    createSegment("ok1", "接口A", "POST", "/api/a", "a", 0),
                    createSegment("FAIL", "接口B", "POST", "/api/b", "FAIL_TRIGGER", 1),
                    createSegment("ok2", "接口C", "POST", "/api/c", "c", 2)
            );

            MultiInterfaceResult result = orch.processInterfaces(
                    segments, "TEST_BANK", "LOAN");

            assertThat(result.getTotalCount()).isEqualTo(3);
            assertThat(result.getSuccessCount()).isEqualTo(2);
            assertThat(result.getFailedCount()).isEqualTo(1);
            assertThat(result.getInterfaces().get(0).getStatus()).isEqualTo("SUCCESS");
            assertThat(result.getInterfaces().get(1).getStatus()).isEqualTo("FAILED");
            assertThat(result.getInterfaces().get(2).getStatus()).isEqualTo("SUCCESS");
        }
    }

    // ── helpers ──

    private InterfaceSegment createSegment(String id, String name, String method,
                                           String endpoint, String text, int index) {
        InterfaceSegment seg = new InterfaceSegment();
        seg.setInterfaceId(id);
        seg.setInterfaceName(name);
        seg.setMethod(method);
        seg.setEndpoint(endpoint);
        seg.setSectionText(text);
        seg.setIndex(index);
        seg.setSplitSource(SplitSource.MARKDOWN_HEADING);
        seg.setSplitConfidence(0.95);
        return seg;
    }

    // ── stub implementations ──

    static class StubRequirementAgent implements com.fundlink.ai.agent.requirement.RequirementAgent {
        @Override
        public RequirementResult analyze(String documentText, String providerCode,
                                          String flowType, List<com.fundlink.ai.agent.diagnosis.DiagnosisResult> prev) {
            if (documentText != null && documentText.contains("FAIL_TRIGGER")) {
                RequirementResult r = new RequirementResult();
                r.setParseError("Simulated failure");
                return r;
            }
            RequirementResult r = new RequirementResult();
            r.setFlowType(flowType);
            r.setProviderConfig(new ProviderConfig());
            return r;
        }
    }
}
