package com.fundlink.ai.agent.intent;

import com.fundlink.ai.agent.loop.LoopTracer;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.RagGateway;
import com.fundlink.ai.gateway.TokenUsage;
import com.fundlink.ai.service.TroubleshootRecorder;
import com.fundlink.ai.tools.ToolCallingLoop;
import com.fundlink.ai.tools.ToolLoopListener;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 问题排查意图处理器。
 * <p>
 * 用户贴入报错日志 → 创建 ai_task → RAG 检索 → Tool Calling 诊断
 * → 写 ai_agent_trace → 回写 RAG 知识库 → 返回诊断结果。
 */
@Slf4j
public class TroubleshootingHandler implements IntentHandler {

    private final LlmGateway llmGateway;
    private final RagGateway ragGateway;
    private final ToolCallingLoop toolLoop;
    private final TroubleshootRecorder recorder;
    private final LoopTracer loopTracer;

    public TroubleshootingHandler(LlmGateway llmGateway, RagGateway ragGateway,
                                   ToolCallingLoop toolLoop,
                                   TroubleshootRecorder recorder,
                                   LoopTracer loopTracer) {
        this.llmGateway = llmGateway;
        this.ragGateway = ragGateway;
        this.toolLoop = toolLoop;
        this.recorder = recorder;
        this.loopTracer = loopTracer;
    }

    @Override
    public IntentType supportedType() {
        return IntentType.TROUBLESHOOTING;
    }

    @Override
    public Object handle(IntentContext ctx) {
        String errorLog = ctx.getUserInput();
        String providerCode = ctx.getProviderCode();
        String traceId = ctx.getTraceId();

        // ── 1. 创建排查任务 ──
        AiTask task = recorder.createTask(errorLog, providerCode, traceId);
        final long taskId = task.getId();
        final long startTime = System.currentTimeMillis();

        try {
            // ── 2. 标记开始诊断 ──
            recorder.markDiagnosing(taskId);

            // ── 3. RAG 检索历史案例 ──
            long ragStart = System.currentTimeMillis();
            List<String> ragExamples = List.of();
            boolean ragSuccess = true;
            String ragError = null;
            try {
                ragExamples = ragGateway.search(errorLog, 3);
                log.info("[Troubleshoot] RAG returned {} examples  taskId={}",
                        ragExamples.size(), taskId);
            } catch (Exception e) {
                ragSuccess = false;
                ragError = e.getMessage();
                log.warn("[Troubleshoot] RAG search failed: {}", e.getMessage());
            }

            // Trace: RAG 检索阶段
            long ragDuration = System.currentTimeMillis() - ragStart;
            loopTracer.trace(taskId, traceId, "RAG_SEARCH", "troubleshoot",
                    "用户报错日志",
                    ragExamples.isEmpty() ? "未找到历史案例" : "找到 " + ragExamples.size() + " 个历史案例",
                    null, ragDuration, ragSuccess, ragError);

            // ── 4. 构建系统 Prompt ──
            String systemPrompt = buildSystemPrompt();
            String userPrompt = buildUserPrompt(ragExamples, errorLog);

            // ── 5. Tool Calling 循环（带 listener 写 trace） ──
            final int[] currentRound = {0};
            final int[] roundToolCount = {0};
            final long[] roundStart = {System.currentTimeMillis()};

            ToolLoopListener traceListener = new ToolLoopListener() {
                @Override
                public void onRoundStart(int round) {
                    currentRound[0] = round;
                    roundToolCount[0] = 0;
                    roundStart[0] = System.currentTimeMillis();
                }

                @Override
                public void onToolCall(int round, String toolName, String args, String result) {
                    roundToolCount[0]++;
                    // 截断结果避免 trace 记录过大
                    String shortResult = result != null && result.length() > 500
                            ? result.substring(0, 500) + "..." : result;
                    String shortArgs = args != null && args.length() > 200
                            ? args.substring(0, 200) + "..." : args;

                    loopTracer.trace(taskId, traceId, "TOOL_LOOP_R" + round, toolName,
                            "参数: " + shortArgs,
                            shortResult,
                            null, 0, true, null);
                }

                @Override
                public void onRoundEnd(int round, int toolCount, long durationMs) {
                    log.info("[Troubleshoot] Round {} complete  {} tools  {}ms  taskId={}",
                            round, toolCount, durationMs, taskId);
                }

                @Override
                public void onComplete(int totalRounds, int totalTools, long totalDurationMs) {
                    log.info("[Troubleshoot] Tool loop complete  {} rounds  {} tools  {}ms  taskId={}",
                            totalRounds, totalTools, totalDurationMs, taskId);
                }
            };

            String analysis = toolLoop.run(systemPrompt, userPrompt, traceId, traceListener);

            // ── 6. 成功 → 更新任务 + 回写 RAG ──
            long totalDuration = System.currentTimeMillis() - startTime;
            recorder.markCompleted(taskId, analysis, ragExamples.size(), currentRound[0]);

            // 写回 RAG 知识库
            task.setProviderCode(providerCode);
            loopTracer.writebackTroubleshootKnowledge(task, analysis, ragExamples.size());

            // Trace: 整体完成
            loopTracer.trace(taskId, traceId, "TROUBLESHOOT_COMPLETE", "troubleshoot",
                    "排查完成",
                    "总耗时 " + totalDuration + "ms | RAG " + ragExamples.size() + " 例 | ToolLoop "
                            + currentRound[0] + " 轮",
                    null, totalDuration, true, null);

            return TroubleshootResult.of(taskId, analysis);

        } catch (Exception e) {
            log.error("[Troubleshoot] Failed  taskId={}: {}", taskId, e.getMessage(), e);

            // 标记失败
            try {
                recorder.markFailed(taskId, e.getMessage());
            } catch (Exception ex) {
                log.error("[Troubleshoot] Failed to mark task as failed", ex);
            }

            return TroubleshootResult.of(taskId,
                    "抱歉，AI 诊断服务暂时不可用，请稍后重试。");
        }
    }

    private String buildSystemPrompt() {
        return """
                你是资金接入系统（FundLink）的运维诊断专家。
                你可以使用以下工具查询系统实时状态来辅助诊断：
                - search_knowledge_base: 搜索知识库中的历史案例和业务知识
                - query_template: 查看 FreeMarker 模板内容
                - query_field_mappings: 查看模板的字段映射配置
                - query_flow_definition: 查看 DAG 流程定义（节点和连线）

                诊断流程：
                1. 先用 search_knowledge_base 搜索类似历史案例
                2. 根据错误信息判断涉及的模板/流程，用工具查询实际配置
                3. 基于工具查询结果 + 历史案例，给出精准诊断

                每次可以用多个工具并行查询。查询到足够信息后给出最终诊断。
                最终诊断请包含：错误原因、影响范围、修复建议。""";
    }

    private String buildUserPrompt(List<String> ragExamples, String errorLog) {
        StringBuilder sb = new StringBuilder();
        if (!ragExamples.isEmpty()) {
            sb.append("## 历史类似案例（来自知识库）\n");
            for (int i = 0; i < ragExamples.size(); i++) {
                sb.append("### 案例 ").append(i + 1).append("\n");
                sb.append(ragExamples.get(i)).append("\n\n");
            }
            sb.append("---\n\n");
        }
        sb.append("## 需要排查的错误\n");
        sb.append(errorLog);
        return sb.toString();
    }

    public static class TroubleshootResult {
        private Long taskId;
        private String analysis;

        public static TroubleshootResult of(Long taskId, String analysis) {
            TroubleshootResult r = new TroubleshootResult();
            r.taskId = taskId;
            r.analysis = analysis;
            return r;
        }

        public Long getTaskId() { return taskId; }
        public void setTaskId(Long taskId) { this.taskId = taskId; }
        public String getAnalysis() { return analysis; }
        public void setAnalysis(String analysis) { this.analysis = analysis; }
    }
}
