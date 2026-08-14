package com.fundlink.ai.agent.loop;

import com.fundlink.ai.agent.diagnosis.DiagnosisAgent;
import com.fundlink.ai.agent.diagnosis.DiagnosisResult;
import com.fundlink.ai.agent.requirement.RequirementAgent;
import com.fundlink.ai.agent.requirement.RequirementResult;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.gateway.TestConfig;
import com.fundlink.ai.mapper.AiTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AgentLoopOrchestrator 核心回归（B1/B5.2/B6.1）：
 * <ul>
 *   <li>双启动竞态：PENDING 条件更新只允许一次生效</li>
 *   <li>决策上下文落库：进入 DECISION_POINT 时 decisionType/summary/options 可查</li>
 *   <li>decide() 先落库：无内存等待方时决策也不丢</li>
 * </ul>
 * 决策超时设为 0 分钟 → 循环在决策点立即 ABORT，测试不阻塞。
 */
@SpringBootTest(classes = TestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "fundlink.loop.decision-timeout-minutes=0")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class AgentLoopOrchestratorTest {

    @Autowired
    private AiTaskMapper taskMapper;

    @Autowired
    private AgentLoopOrchestrator orchestrator;

    @MockBean
    private RequirementAgent requirementAgent;

    @MockBean
    private DiagnosisAgent diagnosisAgent;

    @BeforeEach
    void setUp() {
        reset(requirementAgent, diagnosisAgent);
        taskMapper.delete(null);

        RequirementResult fail = new RequirementResult();
        fail.setParseError("simulated parse error");
        when(requirementAgent.analyze(any(), any(), any(), any())).thenReturn(fail);

        DiagnosisResult diag = new DiagnosisResult();
        diag.setPhase("ANALYZE");
        diag.setRootCause("模拟根因");
        diag.setFixSuggestion("模拟修复建议");
        diag.setConfidence(0.9);
        when(diagnosisAgent.diagnose(any(), any(), any())).thenReturn(diag);
    }

    private AiTask newPendingTask() {
        AiTask t = new AiTask();
        t.setTaskNo("LOOP-TEST-" + System.nanoTime());
        t.setTaskType("LOOP");
        t.setStatus("PENDING");
        t.setFlowType("LOAN");
        t.setProviderCode("TEST");
        t.setDocumentText("test doc");
        t.setCurrentRound(0);
        t.setMaxRounds(1);   // 第一轮失败即进决策点（决策超时 0 → 立即 ABORT）
        t.setCreateTime(LocalDateTime.now());
        t.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(t);
        return t;
    }

    @Test
    @DisplayName("B1: 并发两次 start 只有一次生效")
    void shouldStartOnlyOnceWhenCalledTwice() {
        AiTask t = newPendingTask();

        // TestConfig 的 SyncTaskExecutor 让 @Async 同步执行，两次调用顺序发生
        orchestrator.start(t.getId());
        orchestrator.start(t.getId());

        verify(requirementAgent, times(1)).analyze(any(), any(), any(), any());

        AiTask after = taskMapper.selectById(t.getId());
        assertThat(after.getStatus()).isEqualTo("FAILED"); // 解析失败 → 决策超时 ABORT
    }

    @Test
    @DisplayName("B1: 非 PENDING 任务 start 直接返回")
    void shouldRefuseToStartNonPendingTask() {
        AiTask t = newPendingTask();
        t.setStatus("PUBLISHED");
        taskMapper.updateById(t);

        orchestrator.start(t.getId());

        verify(requirementAgent, never()).analyze(any(), any(), any(), any());
        assertThat(taskMapper.selectById(t.getId()).getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("B5.2: 进入 DECISION_POINT 时决策上下文落库")
    void shouldPersistDecisionContext() {
        AiTask t = newPendingTask();
        orchestrator.start(t.getId());

        AiTask after = taskMapper.selectById(t.getId());
        assertThat(after.getStatus()).isEqualTo("FAILED"); // 决策超时 0 → ABORT
        // 决策上下文在进入 DECISION_POINT 时已落库
        assertThat(after.getDecisionType()).isEqualTo("RECOVERY_EXHAUSTED");
        assertThat(after.getDecisionSummary()).contains("模拟根因");
        assertThat(after.getDecisionOptions()).contains("EDIT_AND_RETRY").contains("ABORT");
    }

    @Test
    @DisplayName("B6.1: decide() 先落库 — 无内存等待方也不丢")
    void shouldPersistDecisionEvenWithoutPendingFuture() {
        AiTask t = newPendingTask();

        DecisionRequest req = new DecisionRequest();
        req.setTaskId(t.getId());
        req.setDecision("ABORT");
        orchestrator.decide(t.getId(), req);

        AiTask after = taskMapper.selectById(t.getId());
        assertThat(after.getDecisionResult()).isEqualTo("ABORT");
        assertThat(after.getDecisionTime()).isNotNull();
    }
}
