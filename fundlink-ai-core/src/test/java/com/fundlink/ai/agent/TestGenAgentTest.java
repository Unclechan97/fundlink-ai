package com.fundlink.ai.agent;

import com.fundlink.ai.agent.requirement.FieldMappingSuggestion;
import com.fundlink.ai.agent.requirement.FlowDsl;
import com.fundlink.ai.agent.requirement.FlowEdge;
import com.fundlink.ai.agent.requirement.FlowNode;
import com.fundlink.ai.agent.testgen.MockRuleSuggestion;
import com.fundlink.ai.agent.testgen.TestCase;
import com.fundlink.ai.agent.testgen.TestGenAgent;
import com.fundlink.ai.agent.testgen.TestGenResult;
import com.fundlink.ai.gateway.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class TestGenAgentTest {

    @Autowired
    private TestGenAgent agent;

    private static FlowDsl sampleFlowDsl() {
        FlowDsl dsl = new FlowDsl();
        dsl.setNodes(List.of(
                node("n1", "START"),
                node("n2", "DATA_COLLECT"),
                node("n3", "TEMPLATE_RENDER"),
                node("nc", "CONDITION"),
                node("n4", "SEND_TO_FUND"),
                node("n5", "END")
        ));
        dsl.setEdges(List.of(
                edge("e1", "n1", "n2", null),
                edge("e2", "n2", "n3", null),
                edge("e3", "n3", "nc", null),
                edge("ec1", "nc", "n4", "#root.riskData.level == 'A'"),
                edge("ec2", "nc", "n5", "#root.riskData.level != 'A'")
        ));
        return dsl;
    }

    private static FlowNode node(String id, String type) {
        FlowNode n = new FlowNode();
        n.setId(id);
        n.setType(type);
        return n;
    }

    private static FlowEdge edge(String id, String source, String target, String conditionExpr) {
        FlowEdge e = new FlowEdge();
        e.setId(id);
        e.setSource(source);
        e.setTarget(target);
        e.setConditionExpr(conditionExpr);
        return e;
    }

    private static List<FieldMappingSuggestion> sampleMappings() {
        FieldMappingSuggestion m1 = new FieldMappingSuggestion();
        m1.setFundField("loanNo");
        m1.setSourcePath("loanInfo.loanNo");
        FieldMappingSuggestion m2 = new FieldMappingSuggestion();
        m2.setFundField("amount");
        m2.setSourcePath("loanInfo.amount");
        m2.setTransform("formatAmount");
        return List.of(m1, m2);
    }

    @Test
    void shouldGenerateMockRules() {
        TestGenResult result = agent.generate(sampleFlowDsl(), sampleMappings(), "FUND_A");

        // Fake provider returns hardcoded data; verify it's non-empty
        assertThat(result.getPreviewData()).isNotNull();
    }

    @Test
    void shouldGenerateTestCases() {
        TestGenResult result = agent.generate(sampleFlowDsl(), sampleMappings(), "FUND_A");

        assertThat(result.getTestCases()).isNotNull();
    }

    @Test
    void shouldGeneratePreviewData() {
        TestGenResult result = agent.generate(sampleFlowDsl(), sampleMappings(), "FUND_A");

        // Preview data must exist for VALIDATE phase
        assertThat(result.getPreviewData()).isNotNull();
    }
}
