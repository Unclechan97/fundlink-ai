package com.fundlink.ai.agent.intent;

import com.fundlink.ai.agent.loop.LoopTracer;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.RagGateway;
import com.fundlink.ai.service.TroubleshootRecorder;
import com.fundlink.ai.tools.ToolCallingLoop;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * B3 验收：RAG 不可用时
 * <ul>
 *   <li>TroubleshootingHandler 不得拿着空上下文继续生成诊断 — 返回"知识库暂不可用"</li>
 *   <li>KnowledgeQaHandler 不做无依据回答 — 返回"知识库暂不可用"</li>
 * </ul>
 */
class RagUnavailableGateTest {

    @Test
    @DisplayName("B3: RAG 不可用 → troubleshoot 返回知识库暂不可用，不调 LLM")
    void troubleshootShouldStopWhenRagUnavailable() {
        RagGateway deadRag = new RagGateway("http://127.0.0.1:1", "k"); // 连接必然失败
        LlmGateway llm = mock(LlmGateway.class);
        TroubleshootRecorder recorder = mock(TroubleshootRecorder.class);
        AiTask task = new AiTask();
        task.setId(1L);
        task.setTaskNo("DIAG-T1");
        when(recorder.createTask(anyString(), any(), anyString())).thenReturn(task);

        TroubleshootingHandler handler = new TroubleshootingHandler(
                llm, deadRag, mock(ToolCallingLoop.class), recorder, mock(LoopTracer.class));

        IntentContext ctx = IntentContext.of("报错日志内容", "diag-test-1");
        Object result = handler.handle(ctx);

        assertThat(result).isInstanceOf(TroubleshootingHandler.TroubleshootResult.class);
        TroubleshootingHandler.TroubleshootResult r =
                (TroubleshootingHandler.TroubleshootResult) result;
        assertThat(r.getAnalysis()).contains("知识库暂不可用");
        // 任务被标记失败
        verify(recorder).markFailed(eq(1L), contains("知识库暂不可用"));
        // 未进入 LLM 诊断
        verify(llm, never()).chat(any());
        verifyNoInteractions(llm);
    }

    @Test
    @DisplayName("B3: RAG 不可用 → 知识问答返回知识库暂不可用，不调 LLM")
    void qaShouldStopWhenRagUnavailable() {
        RagGateway deadRag = new RagGateway("http://127.0.0.1:1", "k");
        LlmGateway llm = mock(LlmGateway.class);

        KnowledgeQaHandler handler = new KnowledgeQaHandler(llm, deadRag);

        KnowledgeQaHandler.QaResult result =
                (KnowledgeQaHandler.QaResult) handler.handle(IntentContext.of("什么是放款", null));

        assertThat(result.getAnswer()).contains("知识库暂不可用");
        verifyNoInteractions(llm);
    }
}
