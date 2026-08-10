package com.fundlink.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.gateway.RagGateway;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * RAG 知识库检索工具。
 */
@Slf4j
public class RagSearchTool implements Tool {

    private final RagGateway ragGateway;
    private final ObjectMapper json = new ObjectMapper();

    private static final ToolDefinition DEF = new ToolDefinition(
            "search_knowledge_base",
            "搜索历史案例和知识库。输入查询关键词，返回相关的历史诊断、配置案例、业务知识。用于排查问题时参考类似的历史情况。",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "query", Map.of("type", "string", "description", "搜索关键词，如'FreeMarker 50002'、'放款申请字段映射'"),
                            "top_k", Map.of("type", "integer", "description", "返回结果数量，默认3，最大10")
                    ),
                    "required", List.of("query")
            )
    );

    public RagSearchTool(RagGateway ragGateway) {
        this.ragGateway = ragGateway;
    }

    @Override
    public ToolDefinition getDefinition() {
        return DEF;
    }

    @Override
    public String execute(ToolCall call) {
        String query = call.arg("query");
        int topK = call.argInt("top_k") != null ? Math.min(call.argInt("top_k"), 10) : 3;

        log.info("[RagSearchTool] query={}  topK={}", query, topK);

        try {
            List<String> results = ragGateway.search(query, topK);
            if (results.isEmpty()) {
                return "{\"found\": false, \"message\": \"知识库未找到相关结果\"}";
            }
            return json.writeValueAsString(Map.of(
                    "found", true,
                    "count", results.size(),
                    "results", results
            ));
        } catch (Exception e) {
            log.error("[RagSearchTool] Failed: {}", e.getMessage());
            return "{\"error\": \"RAG 检索异常: " + e.getMessage() + "\"}";
        }
    }
}
