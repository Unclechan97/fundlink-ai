package com.fundlink.ai.gateway;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fundlink.ai.entity.AiLlmAudit;
import com.fundlink.ai.mapper.AiLlmAuditMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import org.springframework.test.context.ActiveProfiles;

/**
 * TDD Cycle 1: AiLlmAudit Entity + Mapper
 * 验证 LLM 审计日志的持久化能力
 */
@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AiLlmAuditMapperTest {

    @Autowired
    private AiLlmAuditMapper mapper;

    private String callId;
    private String traceId;

    @BeforeEach
    void setUp() {
        callId = UUID.randomUUID().toString();
        traceId = "trace-" + System.currentTimeMillis();
    }

    @Test
    void shouldSaveAndRetrieveAuditRecord() {
        // Given
        AiLlmAudit audit = new AiLlmAudit();
        audit.setCallId(callId);
        audit.setProvider("ANTHROPIC");
        audit.setModel("claude-haiku-4-5-20251001");
        audit.setTokenInput(150);
        audit.setTokenOutput(80);
        audit.setCostAmount(new BigDecimal("0.0005"));
        audit.setLatencyMs(350);
        audit.setSuccess(1);
        audit.setTraceId(traceId);

        // When
        int rows = mapper.insert(audit);

        // Then
        assertThat(rows).isEqualTo(1);
        assertThat(audit.getId()).isNotNull();

        AiLlmAudit saved = mapper.selectById(audit.getId());
        assertThat(saved.getCallId()).isEqualTo(callId);
        assertThat(saved.getProvider()).isEqualTo("ANTHROPIC");
        assertThat(saved.getModel()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(saved.getTokenInput()).isEqualTo(150);
        assertThat(saved.getTokenOutput()).isEqualTo(80);
        assertThat(saved.getCostAmount()).isEqualByComparingTo("0.0005");
        assertThat(saved.getLatencyMs()).isEqualTo(350);
        assertThat(saved.getSuccess()).isEqualTo(1);
        assertThat(saved.getTraceId()).isEqualTo(traceId);
        assertThat(saved.getCreateTime()).isNotNull();
    }

    @Test
    void shouldQueryByTraceId() {
        // Given
        String sharedTraceId = "trace-shared-" + System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            AiLlmAudit audit = new AiLlmAudit();
            audit.setCallId(UUID.randomUUID().toString());
            audit.setProvider("DEEPSEEK");
            audit.setModel("deepseek-chat");
            audit.setTraceId(sharedTraceId);
            mapper.insert(audit);
        }

        // When
        List<AiLlmAudit> results = mapper.selectList(
                new LambdaQueryWrapper<AiLlmAudit>()
                        .eq(AiLlmAudit::getTraceId, sharedTraceId)
        );

        // Then
        assertThat(results).hasSize(3);
        assertThat(results).allMatch(a -> a.getTraceId().equals(sharedTraceId));
    }

    @Test
    void shouldRecordErrorWhenFailureOccurs() {
        // Given
        AiLlmAudit audit = new AiLlmAudit();
        audit.setCallId(callId);
        audit.setProvider("ANTHROPIC");
        audit.setModel("claude-haiku-4-5-20251001");
        audit.setSuccess(0);
        audit.setErrorMsg("Rate limit exceeded");

        // When
        mapper.insert(audit);

        // Then
        AiLlmAudit saved = mapper.selectById(audit.getId());
        assertThat(saved.getSuccess()).isEqualTo(0);
        assertThat(saved.getErrorMsg()).isEqualTo("Rate limit exceeded");
    }
}
