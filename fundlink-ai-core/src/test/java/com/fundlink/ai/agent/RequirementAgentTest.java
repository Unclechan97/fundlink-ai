package com.fundlink.ai.agent;

import com.fundlink.ai.agent.requirement.RequirementAgent;
import com.fundlink.ai.agent.requirement.RequirementResult;
import com.fundlink.ai.gateway.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD Cycle 3: RequirementAgent
 * 验证 AI 需求解析能力：接口文档 → 字段映射 + 流程 DSL
 */
@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class RequirementAgentTest {

    @Autowired
    private RequirementAgent agent;

    private static final String SAMPLE_DOC = """
            资金方: 测试银行
            接口: 放款申请
            请求地址: POST /loan/apply
            请求字段:
              - loanNo (String, 必填) 贷款编号
              - amount (BigDecimal, 必填) 贷款金额
              - customerId (String, 必填) 客户ID
              - customerName (String, 必填) 客户姓名
              - idType (String, 必填) 证件类型
            响应字段:
              - code (String) 响应码
              - message (String) 响应信息
              - loanStatus (String) 贷款状态
            """;

    @Test
    void shouldParseInterfaceDocIntoFieldMappings() {
        RequirementResult result = agent.analyze(SAMPLE_DOC, "TEST_BANK");

        assertThat(result.getFieldMappings()).isNotEmpty();
        // 验证至少包含核心字段映射
        assertThat(result.getFieldMappings()).anyMatch(
                m -> "loanNo".equals(m.getFundField()));
        assertThat(result.getFieldMappings()).anyMatch(
                m -> "amount".equals(m.getFundField()));
    }

    @Test
    void shouldGenerateFlowDsl() {
        RequirementResult result = agent.analyze(SAMPLE_DOC, "TEST_BANK");

        assertThat(result.getFlowDsl()).isNotNull();
        assertThat(result.getFlowDsl().getNodes()).isNotEmpty();
        // 流程必须包含 START 和 END 节点
        assertThat(result.getFlowDsl().getNodes()).anyMatch(
                n -> "START".equals(n.getType()));
        assertThat(result.getFlowDsl().getNodes()).anyMatch(
                n -> "END".equals(n.getType()));
    }

    @Test
    void shouldParseInterfaceSchema() {
        RequirementResult result = agent.analyze(SAMPLE_DOC, "TEST_BANK");

        assertThat(result.getInterfaceSchema()).isNotNull();
        assertThat(result.getInterfaceSchema().getEndpoint())
                .isEqualTo("POST /loan/apply");
        assertThat(result.getInterfaceSchema().getFields()).hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldParseFreeMarkerTemplate() {
        RequirementResult result = agent.analyze(SAMPLE_DOC, "TEST_BANK");

        assertThat(result.getFreeMarkerTemplate()).isNotBlank();
        assertThat(result.getFreeMarkerTemplate()).contains("loanNo");
    }

    @Test
    void shouldParseProviderConfig() {
        RequirementResult result = agent.analyze(SAMPLE_DOC, "TEST_BANK");

        assertThat(result.getProviderConfig()).isNotNull();
        assertThat(result.getProviderConfig().getProviderName()).isNotBlank();
    }
}
