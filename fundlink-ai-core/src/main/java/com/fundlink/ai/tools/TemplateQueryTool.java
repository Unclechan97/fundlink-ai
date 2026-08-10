package com.fundlink.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/**
 * 模板查询工具 — 查询 FreeMarker 模板内容。
 */
@Slf4j
public class TemplateQueryTool implements Tool {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private static final ToolDefinition DEF = new ToolDefinition(
            "query_template",
            "查询 FreeMarker 模板的内容。根据资金方编码(providerCode)查找关联的模板，返回模板 ID、code 和 FreeMarker 内容。",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "provider_code", Map.of("type", "string", "description", "资金方编码"),
                            "template_id", Map.of("type", "integer", "description", "模板ID")
                    ),
                    "required", List.of()
            )
    );

    public TemplateQueryTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public ToolDefinition getDefinition() { return DEF; }

    @Override
    public String execute(ToolCall call) {
        String providerCode = call.arg("provider_code");
        Integer templateId = call.argInt("template_id");

        log.info("[TemplateQueryTool] providerCode={}  templateId={}", providerCode, templateId);

        try {
            List<Map<String, Object>> rows;
            if (templateId != null) {
                rows = jdbc.queryForList(
                        "SELECT t.id, t.template_code, t.content, t.provider_id, p.provider_code " +
                        "FROM fl_template t LEFT JOIN fl_provider p ON t.provider_id = p.id WHERE t.id = ?",
                        templateId);
            } else if (providerCode != null && !providerCode.isBlank()) {
                rows = jdbc.queryForList(
                        "SELECT t.id, t.template_code, t.content, t.provider_id, p.provider_code " +
                        "FROM fl_template t LEFT JOIN fl_provider p ON t.provider_id = p.id WHERE p.provider_code = ?",
                        providerCode);
            } else {
                return "{\"error\": \"请提供 provider_code 或 template_id\"}";
            }

            if (rows.isEmpty()) {
                return "{\"found\": false, \"message\": \"未找到匹配的模板\"}";
            }

            List<Map<String, Object>> safe = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                Map<String, Object> m = new LinkedHashMap<>(row);
                Object content = m.get("content");
                if (content instanceof String s && s.length() > 3000) {
                    m.put("content", s.substring(0, 3000) + "...(截断，共" + s.length() + "字符)");
                }
                safe.add(m);
            }

            return json.writeValueAsString(Map.of("found", true, "count", safe.size(), "templates", safe));
        } catch (Exception e) {
            log.error("[TemplateQueryTool] Failed: {}", e.getMessage());
            return "{\"error\": \"模板查询异常: " + e.getMessage() + "\"}";
        }
    }
}
