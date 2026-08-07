package com.fundlink.ai.agent;

import com.fundlink.ai.agent.diagnosis.DiagnosisAgent;
import com.fundlink.ai.agent.diagnosis.DiagnosisResult;
import com.fundlink.ai.gateway.TestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class DiagnosisAgentTest {

    @Autowired
    private DiagnosisAgent agent;

    @Test
    void shouldIdentifyRootCauseFromErrorLog() {
        String errorDesc = "流程 FL123456 执行失败，错误: field 'contractNo' is null in template render";
        DiagnosisResult result = agent.diagnose("FL123456", errorDesc);

        assertThat(result.getRootCause()).isNotBlank();
        assertThat(result.getConfidence()).isGreaterThan(0.5);
    }

    @Test
    void shouldProvideFixSuggestion() {
        String errorDesc = "模板渲染失败: 字段映射缺失 amount";
        DiagnosisResult result = agent.diagnose("FL789", errorDesc);

        assertThat(result.getFixSuggestion()).isNotBlank();
        assertThat(result.getFixSuggestion()).contains("field_mapping");
    }

    @Test
    void shouldReturnCauseChain() {
        String errorDesc = "放款失败: 风控接口超时 → 数据收集失败 → 流程中断";

        DiagnosisResult result = agent.diagnose("FL456", errorDesc);

        assertThat(result.getCauseChain()).isNotEmpty();
        assertThat(result.getCauseChain().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldHandleUnknownError() {
        DiagnosisResult result = agent.diagnose("FL999", "未知错误");

        assertThat(result.getRootCause()).isNotBlank();
        assertThat(result.getFixSuggestion()).isNotBlank();
    }
}
