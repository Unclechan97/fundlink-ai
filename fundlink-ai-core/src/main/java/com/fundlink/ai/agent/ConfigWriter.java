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
            // Step 1: 创建资金方
            Long providerId = createProvider(result.getProviderConfig(), providerCode);
            r.setProviderId(providerId);
            log.info("[WRITE] Provider created: id={} code={}", providerId, providerCode);

            // Step 2: 创建 FreeMarker 模板（用最终 mappings 重建）
            Long templateId = createTemplate(result.getFreeMarkerTemplate(), result.getFieldMappings(), providerCode, providerId);
            r.setTemplateId(templateId);
            log.info("[WRITE] Template created: id={}", templateId);

            // Step 3: 写入字段映射
            int count = 0;
            if (result.getFieldMappings() != null && templateId != null) {
                for (FieldMappingSuggestion m : result.getFieldMappings()) {
                    createFieldMapping(templateId, m, count);
                    count++;
                }
            }
            r.setMappingCount(count);
            log.info("[WRITE] Mappings: {} rows", count);

            // Step 4: 创建流程定义
            if (result.getFlowDsl() != null && result.getFlowDsl().getNodes() != null) {
                Long flowId = createFlow(result.getFlowDsl(), result.getProviderConfig(),
                        providerCode, flowType, providerId);
                r.setFlowId(flowId);
                log.info("[WRITE] Flow created: id={}", flowId);
            }

            r.setSuccess(true);
        } catch (Exception e) {
            log.error("[WRITE] Failed: {}", e.getMessage(), e);
            r.setSuccess(false);
            r.setError(e.getMessage());
        }
        return r;
    }

    private Long createProvider(ProviderConfig cfg, String providerCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("providerCode", providerCode);
        body.put("providerName", cfg != null && cfg.getProviderName() != null ? cfg.getProviderName() : providerCode);
        body.put("baseUrl", cfg != null && cfg.getBaseUrl() != null ? cfg.getBaseUrl() : "");
        body.put("timeoutMs", 5000);

        Map<String, Object> resp = post("/api/admin/providers", body);
        Object data = resp.get("data");
        if (data instanceof Map) return ((Number) ((Map) data).get("id")).longValue();
        return ((Number) data).longValue();
    }

    private Long createTemplate(String skeleton, List<FieldMappingSuggestion> mappings, String providerCode, Long providerId) throws Exception {
        String templateCode = "AI_" + providerCode + "_" + (System.currentTimeMillis() % 100000);
        String content = rebuildFreeMarker(skeleton, mappings);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateCode", templateCode);
        body.put("templateName", providerCode + " AI生成模板");
        body.put("templateType", "REQUEST");
        body.put("providerId", providerId);
        body.put("content", content);

        Map<String, Object> resp = post("/api/admin/templates", body);
        Object data = resp.get("data");
        if (data instanceof Map) return ((Number) ((Map) data).get("id")).longValue();
        return ((Number) data).longValue();
    }

    /**
     * 根据最终 mappings 重建 FreeMarker 模板
     * - 替换变量名（fundField 可能被用户改了）
     * - 包裹 transform 函数（formatAmount）
     * - 保留结构字段（骨架中存在的非变量 JSON 结构）
     */
    private String rebuildFreeMarker(String skeleton, List<FieldMappingSuggestion> mappings) {
        if (skeleton == null || skeleton.isEmpty()) return "{}";
        // 建立 old_fundField → mapping 的索引（从 AI 生成时的原始字段名）
        // 由于 AI 输出的变量名 = AI 生成的 fundField，我们用 mappings 中的原始名匹配
        // 实际实现：遍历 skeleton 中所有 ${xxx}，找 mapping 中 fundField == xxx 的条目

        String result = skeleton;
        var pattern = java.util.regex.Pattern.compile("\\$\\{([^}]*)\\}");
        var matcher = pattern.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varExpr = matcher.group(1); // e.g. "custName", "formatAmount(applyAmount)"
            String varName = varExpr;
            // 去掉 transform 包裹，拿到纯变量名
            if (varExpr.contains("(")) {
                // formatAmount(xxx) → xxx
                varName = varExpr.replaceAll("^.*\\(|\\).*$", "").trim();
            }

            // 查找对应的 mapping
            FieldMappingSuggestion found = null;
            for (FieldMappingSuggestion m : mappings) {
                if (varName.equals(m.getFundField())) { found = m; break; }
            }

            String replacement;
            if (found != null) {
                String name = found.getFundField();  // 可能是用户修改后的
                if ("formatAmount".equals(found.getTransform())) {
                    replacement = "${formatAmount(" + name + ")}";
                } else if (found.getTransform() != null && !found.getTransform().isEmpty()) {
                    replacement = "${" + found.getTransform() + "(" + name + ")}";
                } else {
                    replacement = "${" + name + "}";
                }
            } else {
                replacement = matcher.group(0); // 保留原样
            }
            matcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private void createFieldMapping(Long templateId, FieldMappingSuggestion m, int sort) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fundField", m.getFundField());
        body.put("sourcePath", m.getSourcePath());
        body.put("transform", m.getTransform());
        body.put("sortOrder", sort);
        post("/api/admin/templates/" + templateId + "/mappings", body);
    }

    private Long createFlow(FlowDsl dsl, ProviderConfig cfg, String providerCode, String flowType, Long providerId)
            throws Exception {
        // 为所有节点补默认值
        for (FlowNode node : dsl.getNodes()) {
            Map<String, Object> data = node.getData();
            if (data == null) data = new LinkedHashMap<>();
            // 补默认 label
            if (!data.containsKey("label")) {
                data.put("label", switch (node.getType()) {
                    case "START" -> "开始";
                    case "END" -> "结束";
                    case "DATA_COLLECT" -> "获取数据";
                    case "TEMPLATE_RENDER" -> "渲染报文";
                    case "SEND_TO_FUND" -> "发送资金方";
                    case "CONDITION" -> "条件判断";
                    default -> "步骤";
                });
            }
            node.setData(data);

            // 补充 SEND_TO_FUND 节点的 url
            if ("SEND_TO_FUND".equals(node.getType())) {
                Map<String, Object> config = (Map<String, Object>) node.getData().get("config");
                if (config == null) {
                    config = new LinkedHashMap<>();
                    node.getData().put("config", config);
                }
                if (!config.containsKey("url") || config.get("url") == null) {
                    String base = cfg != null && cfg.getBaseUrl() != null ? cfg.getBaseUrl() : "http://fund/api";
                    config.put("url", base);
                }
            }
        }

        // CONDITION 节点: 补 label + 同步 expression 到节点 config(给前端展示)
        for (FlowNode node : dsl.getNodes()) {
            if ("CONDITION".equals(node.getType())) {
                List<FlowEdge> outEdges = new ArrayList<>();
                for (FlowEdge e : dsl.getEdges()) {
                    if (node.getId().equals(e.getSource())) outEdges.add(e);
                }
                Map<String, Object> nd = node.getData();
                Map<String, Object> nodeCfg = nd != null ? (Map<String, Object>) nd.get("config") : null;
                if (nodeCfg == null) { nodeCfg = new LinkedHashMap<>(); nd.put("config", nodeCfg); }

                for (int i = 0; i < outEdges.size(); i++) {
                    FlowEdge e = outEdges.get(i);
                    if (e.getLabel() == null || e.getLabel().isEmpty()) {
                        if (e.getConditionExpr() != null && !e.getConditionExpr().isEmpty()) {
                            e.setLabel(i == 0 ? "通过" : "拒绝");
                        } else if (i == outEdges.size() - 1 && outEdges.stream().anyMatch(x -> x.getConditionExpr() != null)) {
                            e.setLabel("否则");
                        }
                    }
                    // 第一个有条件表达式的边 → 同步到节点，前端点击CONDITION节点可以展示
                    if (i == 0 && e.getConditionExpr() != null && !e.getConditionExpr().isEmpty() && !nodeCfg.containsKey("expression")) {
                        nodeCfg.put("expression", e.getConditionExpr());
                    }
                }
            }
        }

        Map<String, Object> graphData = Map.of("nodes", dsl.getNodes(), "edges", dsl.getEdges());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowCode", "AI_" + providerCode + "_" + flowType);
        body.put("flowName", providerCode + " AI生成流程");
        body.put("flowType", flowType);
        body.put("providerId", providerId);
        body.put("graphData", json.writeValueAsString(graphData));

        Map<String, Object> resp = post("/api/admin/flows", body);
        Object data = resp.get("data");
        if (data instanceof Number) return ((Number) data).longValue();
        if (data instanceof Map) {
            Object id = ((Map) data).get("id");
            if (id instanceof Number) return ((Number) id).longValue();
            return Long.parseLong(id.toString());
        }
        return 0L;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Object body) throws Exception {
        URI uri = new URI(fundlinkUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        String b = json.writeValueAsString(body);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(b.getBytes(StandardCharsets.UTF_8));
        }
        if (conn.getResponseCode() >= 400) {
            String err = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("FundLink API error " + conn.getResponseCode() + ": " + err);
        }
        return json.readValue(conn.getInputStream(), Map.class);
    }

    public static class WriteResult {
        private boolean success;
        private Long providerId;
        private Long templateId;
        private int mappingCount;
        private Long flowId;
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
