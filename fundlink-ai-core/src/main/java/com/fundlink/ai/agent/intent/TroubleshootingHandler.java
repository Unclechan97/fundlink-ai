package com.fundlink.ai.agent.intent;

import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.RagGateway;
import com.fundlink.ai.tools.ToolCallingLoop;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 问题排查意图处理器。
 * 用户贴入报错日志 → RAG 检索 → Tool Calling 诊断。
 */
@Slf4j
public class TroubleshootingHandler implements IntentHandler {

    private final LlmGateway llmGateway;
    private final RagGateway ragGateway;
    private final ToolCallingLoop toolLoop;

    public TroubleshootingHandler(LlmGateway llmGateway, RagGateway ragGateway,
                                   ToolCallingLoop toolLoop) {
        this.llmGateway = llmGateway;
        this.ragGateway = ragGateway;
        this.toolLoop = toolLoop;
    }

    @Override
    public IntentType supportedType() {
        return IntentType.TROUBLESHOOTING;
    }

    @Override
    public Object handle(IntentContext ctx) {
        List<String> ragExamples = List.of();
        try {
            ragExamples = ragGateway.search(ctx.getUserInput(), 3);
            log.info("[Troubleshoot] RAG returned {} examples", ragExamples.size());
        } catch (Exception e) {
            log.warn("[Troubleshoot] RAG search failed: {}", e.getMessage());
        }

        String systemPrompt = """
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
        sb.append(ctx.getUserInput());

        try {
            String analysis = toolLoop.run(systemPrompt, sb.toString(), ctx.getTraceId());
            return TroubleshootResult.of(analysis);
        } catch (Exception e) {
            log.error("[Troubleshoot] Tool loop failed: {}", e.getMessage(), e);
            return TroubleshootResult.of("抱歉，AI 诊断服务暂时不可用，请稍后重试。");
        }
    }

    public static class TroubleshootResult {
        private String analysis;
        public static TroubleshootResult of(String analysis) {
            TroubleshootResult r = new TroubleshootResult();
            r.analysis = analysis;
            return r;
        }
        public String getAnalysis() { return analysis; }
        public void setAnalysis(String a) { this.analysis = a; }
    }
}
