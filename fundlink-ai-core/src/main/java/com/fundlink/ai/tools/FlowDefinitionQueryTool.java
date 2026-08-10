package com.fundlink.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 流程定义查询工具 — 查询 DAG 流程的节点和连线配置。
 */
@Slf4j
public class FlowDefinitionQueryTool implements Tool {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private static final ToolDefinition DEF = new ToolDefinition(
            "query_flow_definition",
            "查询流程定义(DAG)的配置。根据资金方编码(providerCode)查找关联的流程定义，返回流程code、状态、graphData(节点和连线JSON)。用于排查流程执行中的CONDITION路由、节点顺序问题。",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "provider_code", Map.of("type", "string",
                                    "description", "资金方编码，如 FUND_A"),
                            "flow_code", Map.of("type", "string",
                                    "description", "流程编码（如果已知）")
                    ),
                    "required", List.of()
            )
    );

    public FlowDefinitionQueryTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolDefinition getDefinition() {
        return DEF;
    }

    @Override
    public String execute(ToolCall call) {
        String providerCode = call.arg("provider_code");
        String flowCode = call.arg("flow_code");

        log.info("[FlowDefinitionQueryTool] providerCode={}  flowCode={}", providerCode, flowCode);

        try {
            List<Map<String, Object>> rows;
            if (flowCode != null && !flowCode.isBlank()) {
                rows = jdbc.queryForList(
                        "SELECT fd.flow_code, fd.flow_name, fd.flow_type, fd.graph_data, fd.status, " +
                        "fd.provider_id, p.provider_code " +
                        "FROM fl_flow_definition fd " +
                        "LEFT JOIN fl_provider p ON fd.provider_id = p.id " +
                        "WHERE fd.flow_code = ?", flowCode);
            } else if (providerCode != null && !providerCode.isBlank()) {
                rows = jdbc.queryForList(
                        "SELECT fd.flow_code, fd.flow_name, fd.flow_type, fd.graph_data, fd.status, " +
                        "fd.provider_id, p.provider_code " +
                        "FROM fl_flow_definition fd " +
                        "LEFT JOIN fl_provider p ON fd.provider_id = p.id " +
                        "WHERE p.provider_code = ?", providerCode);
            } else {
                return "{\"error\": \"请提供 provider_code 或 flow_code\"}";
            }

            if (rows.isEmpty()) {
                return "{\"found\": false, \"message\": \"未找到匹配的流程定义\"}";
            }

            // graph_data 是 JSON 文本，确保可读性
            return json.writeValueAsString(Map.of(
                    "found", true,
                    "count", rows.size(),
                    "flows", rows
            ));
        } catch (Exception e) {
            log.error("[FlowDefinitionQueryTool] Failed: {}", e.getMessage());
            return "{\"error\": \"流程查询异常: " + e.getMessage() + "\"}";
        }
    }
}
