package com.fundlink.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.requirement.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
public class ConfigWriter {

    private final String fundlinkUrl;
    private final ObjectMapper json = new ObjectMapper();

    public ConfigWriter(@Value("${fundlink.admin.base-url:http://localhost:8080}") String fundlinkUrl) {
        this.fundlinkUrl = fundlinkUrl;
    }

    public WriteResult writeAll(RequirementResult result, String providerCode, String flowType) {
        WriteResult r = new WriteResult();
        try {
            Long providerId = getOrCreateProvider(result.getProviderConfig(), providerCode);
            r.setProviderId(providerId);
            log.info("[WRITE] Provider id={}", providerId);

            Long templateId = getOrCreateTemplate(result.getFieldMappings(), providerCode, providerId);
            r.setTemplateId(templateId);
            log.info("[WRITE] Template id={}", templateId);

            // Mappings: 先删后建（幂等）
            if (templateId != null && result.getFieldMappings() != null) {
                deleteExistingMappings(templateId);
                int count = 0;
                for (FieldMappingSuggestion m : result.getFieldMappings()) {
                    if (m.getFundField() != null && !m.getFundField().isBlank()) {
                        createFieldMapping(templateId, m, count);
                        count++;
                    }
                }
                r.setMappingCount(count);
                log.info("[WRITE] Mappings {} rows", count);
            }

            if (result.getFlowDsl() != null && result.getFlowDsl().getNodes() != null) {
                Long flowId = getOrCreateFlow(result.getFlowDsl(), result.getProviderConfig(),
                        providerCode, flowType, providerId);
                r.setFlowId(flowId);
                log.info("[WRITE] Flow id={}", flowId);
            }
            r.setSuccess(true);
        } catch (Exception e) {
            log.error("[WRITE] Failed: {}", e.getMessage(), e);
            r.setSuccess(false);
            r.setError(e.getMessage());
        }
        return r;
    }

    // ── Provider（幂等：先查后建）──
    private Long getOrCreateProvider(ProviderConfig cfg, String code) throws Exception {
        Long existing = findProviderByCode(code);
        if (existing != null) {
            log.info("[WRITE] Provider exists code={} id={}", code, existing);
            return existing;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerCode", code);
        body.put("providerName", cfg != null && cfg.getProviderName() != null ? cfg.getProviderName() : code);
        body.put("baseUrl", cfg != null && cfg.getBaseUrl() != null ? cfg.getBaseUrl() : "");
        body.put("timeoutMs", 5000);
        Map<String, Object> resp = post("/api/admin/providers", body);
        return extractId(resp);
    }

    private Long findProviderByCode(String code) throws Exception {
        List<Map> records = listAll("/api/admin/providers");
        for (Map r : records) {
            if (code.equals(r.get("providerCode"))) {
                Object id = r.get("id");
                return id instanceof Number ? ((Number) id).longValue() : Long.parseLong(id.toString());
            }
        }
        return null;
    }

    // ── Template（幂等：code 用 providerCode 固定，重复则复用）──
    private Long getOrCreateTemplate(List<FieldMappingSuggestion> mappings,
                                      String providerCode, Long providerId) throws Exception {
        String templateCode = "AI_" + providerCode;
        Long existing = findTemplateByCode(templateCode);
        if (existing != null) {
            log.info("[WRITE] Template exists code={} id={}", templateCode, existing);
            return existing;
        }

        String content = buildFreeMarker(mappings);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateCode", templateCode);
        body.put("templateName", providerCode + " AI生成模板");
        body.put("templateType", "REQUEST");
        body.put("providerId", providerId);
        body.put("content", content);

        Map<String, Object> resp = post("/api/admin/templates", body);
        return extractId(resp);
    }

    private Long findTemplateByCode(String code) throws Exception {
        List<Map> records = listAll("/api/admin/templates");
        for (Map r : records) {
            if (code.equals(r.get("templateCode"))) {
                Object id = r.get("id");
                return id instanceof Number ? ((Number) id).longValue() : Long.parseLong(id.toString());
            }
        }
        return null;
    }

    private String buildFreeMarker(List<FieldMappingSuggestion> mappings) {
        List<FieldMappingSuggestion> valid = new ArrayList<>();
        if (mappings != null) {
            for (FieldMappingSuggestion m : mappings) {
                if (m.getFundField() != null && !m.getFundField().isBlank()) valid.add(m);
            }
        }
        StringBuilder content = new StringBuilder("{\n");
        for (int i = 0; i < valid.size(); i++) {
            FieldMappingSuggestion m = valid.get(i);
            String name = m.getFundField();
            String comma = (i < valid.size() - 1) ? "," : "";
            String sp = m.getSourcePath() != null ? m.getSourcePath() : name;
            String tf = m.getTransform();
            if (tf != null && !tf.isBlank()) {
                content.append("  \"").append(name).append("\": \"${").append(tf).append("(").append(sp).append(")}\"").append(comma).append("\n");
            } else {
                content.append("  \"").append(name).append("\": \"${").append(sp).append("}\"").append(comma).append("\n");
            }
        }
        content.append("}");
        return content.toString();
    }

    // ── Mappings（幂等：先删后建）──
    private void deleteExistingMappings(Long templateId) throws Exception {
        String path = "/api/admin/templates/" + templateId + "/mappings";
        try {
            String resp = get(path);
            if (resp != null && !resp.isBlank()) {
                // 解包 {code:0, data:[...]} → 取 data
                Map<String, Object> root = json.readValue(resp, Map.class);
                Object data = root.get("data");
                if (data instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map> existing = (List<Map>) data;
                    for (Map item : existing) {
                        Object id = item.get("id");
                        if (id != null) {
                            delete("/api/admin/templates/" + templateId + "/mappings/" + id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[WRITE] Failed to delete old mappings for template {}: {}", templateId, e.getMessage());
        }
    }

    // ── FieldMapping ──
    private void createFieldMapping(Long templateId, FieldMappingSuggestion m, int sort) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fundField", m.getFundField());
        body.put("sourcePath", m.getSourcePath());
        body.put("transform", m.getTransform());
        body.put("sortOrder", sort);
        post("/api/admin/templates/" + templateId + "/mappings", body);
    }

    // ── Flow（幂等：先查后建）──
    private Long getOrCreateFlow(FlowDsl dsl, ProviderConfig cfg, String code, String type, Long pid) throws Exception {
        String flowCode = type + "_" + code;  // 匹配上游 flowType_providerCode
        Long existing = findFlowByCode(flowCode);
        if (existing != null) {
            log.info("[WRITE] Flow exists code={} id={}", flowCode, existing);
            return existing;
        }

        enrichFlowDsl(dsl, cfg);
        Map<String, Object> graphData = Map.of("nodes", dsl.getNodes(), "edges",
                dsl.getEdges() != null ? dsl.getEdges() : Collections.emptyList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowCode", flowCode);
        body.put("flowName", code + " AI生成流程");
        body.put("flowType", type);
        body.put("providerId", pid);
        body.put("graphData", json.writeValueAsString(graphData));
        Map<String, Object> resp = post("/api/admin/flows", body);
        return extractId(resp);
    }

    private Long findFlowByCode(String code) throws Exception {
        List<Map> records = listAll("/api/admin/flows");
        for (Map r : records) {
            if (code.equals(r.get("flowCode"))) {
                Object id = r.get("id");
                return id instanceof Number ? ((Number) id).longValue() : Long.parseLong(id.toString());
            }
        }
        return null;
    }

    private void enrichFlowDsl(FlowDsl dsl, ProviderConfig cfg) {
        for (FlowNode node : dsl.getNodes()) {
            Map<String, Object> data = node.getData();
            if (data == null) data = new LinkedHashMap<>();
            if (!data.containsKey("label")) {
                data.put("label", switch (node.getType()) {
                    case "START" -> "开始"; case "END" -> "结束";
                    case "DATA_COLLECT" -> "获取数据"; case "TEMPLATE_RENDER" -> "渲染报文";
                    case "SEND_TO_FUND" -> "发送资金方"; case "CONDITION" -> "条件判断";
                    default -> "步骤";
                });
            }
            node.setData(data);

            if ("SEND_TO_FUND".equals(node.getType())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) data.get("config");
                if (config == null) { config = new LinkedHashMap<>(); data.put("config", config); }
                if (!config.containsKey("url") || config.get("url") == null) {
                    config.put("url", cfg != null && cfg.getBaseUrl() != null ? cfg.getBaseUrl() : "http://fund/api");
                }
            }
        }

        for (FlowNode node : dsl.getNodes()) {
            if (!"CONDITION".equals(node.getType())) continue;
            List<FlowEdge> outEdges = new ArrayList<>();
            if (dsl.getEdges() != null) {
                for (FlowEdge e : dsl.getEdges()) {
                    if (node.getId().equals(e.getSource())) outEdges.add(e);
                }
            }
            Map<String, Object> nd = node.getData();
            @SuppressWarnings("unchecked")
            Map<String, Object> ndCfg = nd != null ? (Map<String, Object>) nd.get("config") : null;
            if (ndCfg == null) { ndCfg = new LinkedHashMap<>(); nd.put("config", ndCfg); }
            for (int i = 0; i < outEdges.size(); i++) {
                FlowEdge e = outEdges.get(i);
                if (e.getLabel() == null || e.getLabel().isEmpty()) {
                    if (e.getConditionExpr() != null && !e.getConditionExpr().isEmpty()) {
                        e.setLabel(i == 0 ? "通过" : "拒绝");
                    } else if (i == outEdges.size() - 1 &&
                            outEdges.stream().anyMatch(x -> x.getConditionExpr() != null && !x.getConditionExpr().isEmpty())) {
                        e.setLabel("否则");
                    }
                }
                if (i == 0 && e.getConditionExpr() != null && !e.getConditionExpr().isEmpty()
                        && !ndCfg.containsKey("expression")) {
                    ndCfg.put("expression", e.getConditionExpr());
                }
            }
        }
    }

    // ── HTTP helpers ──
    private List<Map> listAll(String path) throws Exception {
        String resp = get(path + "?page=1&size=200");
        if (resp == null || resp.isBlank()) return Collections.emptyList();
        Map<String, Object> root = json.readValue(resp, Map.class);
        Object data = root.get("data");
        if (data instanceof Map) {
            Object records = ((Map) data).get("records");
            if (records instanceof List) return (List<Map>) records;
        }
        if (data instanceof List) return (List<Map>) data;
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) throws Exception {
        URI uri = new URI(fundlinkUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        String b = json.writeValueAsString(body);
        try (OutputStream os = conn.getOutputStream()) { os.write(b.getBytes(StandardCharsets.UTF_8)); }
        if (conn.getResponseCode() >= 400) {
            String err = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("FundLink API error " + conn.getResponseCode() + ": " + err);
        }
        return json.readValue(conn.getInputStream(), Map.class);
    }

    private String get(String path) throws Exception {
        URI uri = new URI(fundlinkUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (conn.getResponseCode() == 200) {
            return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
        return null;
    }

    private void delete(String path) throws Exception {
        URI uri = new URI(fundlinkUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.getResponseCode(); // fire and forget
    }

    private Long extractId(Map<String, Object> resp) {
        Object data = resp.get("data");
        if (data instanceof Number) return ((Number) data).longValue();
        if (data instanceof Map) {
            Object id = ((Map) data).get("id");
            if (id instanceof Number) return ((Number) id).longValue();
            if (id != null) return Long.parseLong(id.toString());
        }
        return 0L;
    }

    public static class WriteResult {
        private boolean success;
        private Long providerId, templateId, flowId;
        private int mappingCount;
        private String error;
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean s) { this.success = s; }
        public Long getProviderId() { return providerId; }
        public void setProviderId(Long p) { this.providerId = p; }
        public Long getTemplateId() { return templateId; }
        public void setTemplateId(Long t) { this.templateId = t; }
        public int getMappingCount() { return mappingCount; }
        public void setMappingCount(int c) { this.mappingCount = c; }
        public Long getFlowId() { return flowId; }
        public void setFlowId(Long f) { this.flowId = f; }
        public String getError() { return error; }
        public void setError(String e) { this.error = e; }
    }
}
