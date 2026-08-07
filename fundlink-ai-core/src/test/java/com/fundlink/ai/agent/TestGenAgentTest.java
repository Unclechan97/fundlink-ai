package com.fundlink.ai.agent;

import com.fundlink.ai.agent.testgen.MockRuleSuggestion;
import com.fundlink.ai.agent.testgen.TestCase;
import com.fundlink.ai.agent.testgen.TestGenAgent;
import com.fundlink.ai.agent.testgen.TestGenResult;
import com.fundlink.ai.gateway.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TestGenAgentTest {

    @Autowired
    private TestGenAgent agent;

    private static final String SAMPLE_INTERFACE = """
        接口: 放款申请 POST /loan/apply
        字段:
        - loanNo(String,必填) 贷款编号
        - amount(BigDecimal,必填) 贷款金额,范围1000-500000
        - customerName(String,必填) 客户姓名
        - loanStatus(String) 贷款状态: APPROVED/REJECTED/PENDING
        """;

    @Test
    void shouldGenerateMockRules() {
        TestGenResult result = agent.generate(SAMPLE_INTERFACE, "FUND_A", 10);

        assertThat(result.getMockRules()).isNotEmpty();
        // 至少有一条默认规则(无条件匹配)
        assertThat(result.getMockRules()).anyMatch(r ->
                r.getMatchExpr() == null || r.getMatchExpr().isEmpty());
        // 应该有针对特定场景的规则
        assertThat(result.getMockRules().size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void shouldGenerateTestCases() {
        TestGenResult result = agent.generate(SAMPLE_INTERFACE, "FUND_A", 10);

        assertThat(result.getTestCases()).isNotEmpty();
        // 应该包含正常场景
        assertThat(result.getTestCases()).anyMatch(tc ->
                "NORMAL".equals(tc.getScenarioType()));
        // 应该包含异常场景
        assertThat(result.getTestCases()).anyMatch(tc ->
                "ERROR".equals(tc.getScenarioType()) || "BOUNDARY".equals(tc.getScenarioType()));
    }

    @Test
    void shouldCoverBoundaryScenarios() {
        TestGenResult result = agent.generate(SAMPLE_INTERFACE, "FUND_A", 10);

        assertThat(result.getTestCases()).anyMatch(tc ->
                "BOUNDARY".equals(tc.getScenarioType()));
    }

    @Test
    void shouldIncludeValidationInMockRules() {
        TestGenResult result = agent.generate(SAMPLE_INTERFACE, "FUND_A", 10);

        for (MockRuleSuggestion rule : result.getMockRules()) {
            assertThat(rule.getRuleName()).isNotBlank();
            assertThat(rule.getResponseJson()).isNotBlank();
            assertThat(rule.getSourceCode()).isNotBlank();
        }
    }
}
