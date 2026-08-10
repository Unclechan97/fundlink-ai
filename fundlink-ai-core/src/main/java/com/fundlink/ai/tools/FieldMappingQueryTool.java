package com.fundlink.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

/**
 * 字段映射查询工具 — 查询模板关联的字段映射配置。
 */
@Slf4j
public class FieldMappingQueryTool implements Tool {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private static final ToolDefinition DEF = new ToolDefinition(
            "query_field_mappings",
            "查询模板的字段映射配置。返回 fundField(接口字段名)、sourcePath(数据源路径)、fieldType(字段类型)、transform(转换函数)、defaultValue(默认值)。用于排查字段映射缺失或路径错误。",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "template_id", Map.of("type", "integer",
                                    "description", "模板ID，可通过 query_template 工具获取")
                    ),
                    "required", List.of("template_id")
            )
    );

    public FieldMappingQueryTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolDefinition getDefinition() {
        return DEF;
    }

    @Override
    public String execute(ToolCall call) {
        Integer templateId = call.argInt("template_id");
        if (templateId == null) {
            return "{\"error\": \"缺少 template_id 参数\"}";
        }

        log.info("[FieldMappingQueryTool] templateId={}", templateId);

        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT fund_field, source_path, field_type, transform, default_value, sort_order " +
                    "FROM fl_field_mapping WHERE template_id = ? " +
                    "ORDER BY sort_order, id", templateId);

            if (rows.isEmpty()) {
                return "{\"found\": false, \"message\": \"该模板无字段映射配置\"}";
            }

            return json.writeValueAsString(Map.of(
                    "found", true,
                    "count", rows.size(),
                    "mappings", rows
            ));
        } catch (Exception e) {
            log.error("[FieldMappingQueryTool] Failed: {}", e.getMessage());
            return "{\"error\": \"字段映射查询异常: " + e.getMessage() + "\"}";
        }
    }
}
