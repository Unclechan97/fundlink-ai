package com.fundlink.ai.gateway;

import com.fundlink.ai.entity.AiLlmAudit;
import com.fundlink.ai.mapper.AiLlmAuditMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TDD Cycle 2: LlmGateway
 */
@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class LlmGatewayTest {

    @Autowired
    private LlmGateway gateway;

    @Autowired
    private AiLlmAuditMapper auditMapper;

    @Test
    void shouldReturnChatResponseForKnownProvider() {
        LlmRequest request = LlmRequest.of("qwen", "qwen-plus",
                "请生成资金方字段映射", "trace-001");

        LlmResponse response = gateway.chat(request);

        assertThat(response.getContent()).isNotBlank();
        assertThat(response.getProvider()).isEqualTo("qwen");
        assertThat(response.getModel()).isEqualTo("qwen-plus");
        assertThat(response.getTokenUsage()).isNotNull();
    }

    @Test
    void shouldAuditEveryLlmCall() {
        LlmRequest request = LlmRequest.of("qwen", "qwen-plus",
                "测试审计", "trace-audit");

        gateway.chat(request);

        List<AiLlmAudit> audits = auditMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiLlmAudit>()
                        .eq(AiLlmAudit::getTraceId, "trace-audit")
        );
        assertThat(audits).hasSize(1);
        AiLlmAudit audit = audits.get(0);
        assertThat(audit.getProvider()).isEqualTo("QWEN");
        assertThat(audit.getSuccess()).isEqualTo(1);
    }

    @Test
    void shouldFallbackWhenExplicitProviderMissing() {
        // Request non-existent explicit provider — should fall back via chain to deepseek
        LlmRequest request = LlmRequest.of("nonexistent", "any",
                "test fallback", "trace-fallback");

        LlmResponse response = gateway.chat(request);

        // Fallback succeeded via chain (deepseek fake is registered)
        assertThat(response.getContent()).isNotBlank();
        // Provider should be the one that actually served the request
        assertThat(response.getProvider()).isIn("deepseek", "qwen");
    }

    @Test
    void shouldTrackTokenUsageAndCost() {
        LlmRequest request = LlmRequest.of("qwen", "qwen-plus",
                "测试 token 统计", "trace-tokens");

        LlmResponse response = gateway.chat(request);

        assertThat(response.getTokenUsage().getInputTokens()).isGreaterThan(0);
        assertThat(response.getTokenUsage().getOutputTokens()).isGreaterThan(0);
    }
}
