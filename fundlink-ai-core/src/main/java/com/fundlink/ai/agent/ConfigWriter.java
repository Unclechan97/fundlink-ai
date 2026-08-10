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

    /** Phase 3: 带 interfaceId 的写入 */
    public WriteResult writeAll(RequirementResult result, String providerCode, String flowType,
                                 String interfaceId) {
        WriteResult r = writeAllInternal(result, providerCode, flowType, interfaceId);
        return r;
    }

    public WriteResult writeAll(RequirementResult result, String providerCode, String flowType) {
        return writeAllInternal(result, providerCode, flowType, null);
    }

    private WriteResult writeAllInternal(RequirementResult result, String providerCode,
                                         String flowType, String interfaceId) {
        WriteResult r = new WriteResult();
        try {
            Long providerId = getOrCreateProvider(result.getProviderConfig(), providerCode);
            r.setProviderId(providerId);
            log.info("[WRITE] Provider id={}", providerId);

            Long templateId = getOrCreateTemplate(result.getFieldMappings(), providerCode,
                    providerId, interfaceId);
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
                        providerCode, flowType, providerId, interfaceId);
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
                                      String providerCode, Long providerId,
                                      String interfaceId) throws Exception {
        String templateCode = "AI_" + providerCode;
        if (interfaceId != null && !interfaceId.isBlank()) {
            templateCode = templateCode + "_" + interfaceId;
        }
        String content = buildFreeMarker(mappings);
        log.info("[WRITE] Template content: {}", content.replaceAll("\\s+", " "));

        Long existing = findTemplateByCode(templateCode);
        if (existing != null) {
            log.info("[WRITE] Template exists code={} id={} — updating content", templateCode, existing);
            // PUT 更新 content，防止上一轮的 marker 或遗漏字段永久残留
            Map<String, Object> updateBody = new LinkedHashMap<>();
            updateBody.put("templateCode", templateCode);
            updateBody.put("templateName", providerCode + " AI生成模板");
            updateBody.put("templateType", "REQUEST");
            updateBody.put("providerId", providerId);
            updateBody.put("content", content);
            String path = "/api/admin/templates/" + existing;
            put(path, updateBody);
            return existing;
        }

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
                String f = m.getFundField();
                if (f != null && !f.isBlank() && !"null".equalsIgnoreCase(f.trim())) valid.add(m);
            }
        }

        // 构建嵌套树（支持点号路径和数组路径）
        JsonNode root = buildTree(valid);
        StringBuilder content = new StringBuilder();
        writeNode(content, null, root, 0);
        return content.toString();
    }

    // ── 嵌套 JSON 树构建 ──

    private static class JsonNode {
        String leafExpr;                         // 叶子节点: FreeMarker 表达式
        boolean isArray;                         // 数组节点
        String listSource;                       // 数组数据源路径 (如 loanInfo.repayPeriods)
        Map<String, JsonNode> children;          // 对象节点: 子字段

        JsonNode leaf(String expr) { this.leafExpr = expr; return this; }
        JsonNode object() { this.children = new LinkedHashMap<>(); return this; }
    }

    /** 将字段映射列表转为嵌套树 */
    private JsonNode buildTree(List<FieldMappingSuggestion> mappings) {
        JsonNode root = new JsonNode();
        root.children = new LinkedHashMap<>();

        for (FieldMappingSuggestion m : mappings) {
            String name = m.getFundField();
            String rawSp = m.getSourcePath();
            boolean hasSp = rawSp != null && !rawSp.isBlank()
                    && !"null".equalsIgnoreCase(rawSp.trim());
            String sp = hasSp ? rawSp : null;
            String tf = m.getTransform();
            boolean hasTf = tf != null && !tf.isBlank()
                    && !"null".equalsIgnoreCase(tf.trim());

            // 构建 FreeMarker 表达式 — 去掉 source_path 中的 []（<#list> 已由树结构处理）
            String expr;
            if (sp == null) {
                expr = "\"\"";  // 无匹配 → 空字符串占位
                log.warn("[WRITE] Unmapped field: {} — using empty placeholder (TODO)", name);
            } else {
                String cleanSp = sp.replace("[]", "").replace("..", ".");
                if (hasTf) {
                    expr = "\"${" + tf + "(" + cleanSp + ")}\"";
                } else {
                    expr = "\"${" + cleanSp + "}\"";
                }
            }

            // 解析路径: "a.b.c" → ["a","b","c"]; "arr[].x" → ["arr","[]","x"]
            List<String> path = parsePath(name);
            insertTree(root, path, 0, expr, hasSp ? rawSp : null);
        }
        return root;
    }

    /** 解析 fundField 路径: "repayAccount.bankCode" → ["repayAccount","bankCode"] */
    private List<String> parsePath(String fundField) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < fundField.length(); i++) {
            char c = fundField.charAt(i);
            if (c == '.') {
                if (current.length() > 0) { segments.add(current.toString()); current.setLength(0); }
            } else if (c == '[') {
                // "arr[].x" → push "arr", then "[]"
                if (current.length() > 0) { segments.add(current.toString()); current.setLength(0); }
                // consume until ']'
                while (i < fundField.length() && fundField.charAt(i) != ']') i++;
                segments.add("[]");  // marker for array element
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) segments.add(current.toString());
        return segments;
    }

    /** 递归插入路径到树，rawSp 为原始 source_path（用于提取数组数据源变量名） */
    private void insertTree(JsonNode node, List<String> path, int idx, String expr, String rawSp) {
        if (idx >= path.size()) return;
        String seg = path.get(idx);

        if ("[]".equals(seg)) {
            // 数组节点 — 从 source_path 提取列表变量名
            if (node.children == null) { node.children = new LinkedHashMap<>(); }
            node.isArray = true;
            if (node.listSource == null && rawSp != null) {
                node.listSource = extractListSource(rawSp);
            }
            JsonNode child = node.children.computeIfAbsent("[]", k -> new JsonNode().object());
            insertTree(child, path, idx + 1, expr, rawSp);
        } else if (idx == path.size() - 1) {
            // 叶子
            if (node.children == null) node.children = new LinkedHashMap<>();
            node.children.put(seg, new JsonNode().leaf(expr));
        } else {
            // 中间节点
            if (node.children == null) node.children = new LinkedHashMap<>();
            JsonNode child = node.children.computeIfAbsent(seg, k -> new JsonNode().object());
            insertTree(child, path, idx + 1, expr, rawSp);
        }
    }

    /** 从 source_path 提取列表变量名: "loanInfo.repayPeriods[].periodNo" → "loanInfo.repayPeriods" */
    private String extractListSource(String rawSp) {
        // 找到 [] 的位置，往前到上一个 . 或开头
        int bracket = rawSp.indexOf("[]");
        if (bracket < 0) return null;
        String prefix = rawSp.substring(0, bracket);
        // 去掉末尾可能残留的 .
        if (prefix.endsWith(".")) prefix = prefix.substring(0, prefix.length() - 1);
        return prefix;
    }

    /** 递归输出 JSON 字符串 */
    private void writeNode(StringBuilder sb, String fieldName, JsonNode node, int indent) {
        String pad = "  ".repeat(indent);
        if (node.leafExpr != null) {
            // 叶子 — 不需要递归，由父级输出 key: value
            sb.append(node.leafExpr);
        } else if (node.isArray && node.children != null) {
            JsonNode item = node.children.get("[]");
            // 所有子字段都无数据源 → 空数组
            if (allEmpty(item)) {
                sb.append("[]");
            } else {
                String listVar = node.listSource != null ? node.listSource : (fieldName != null ? fieldName : "items");
                sb.append("[\n");
                sb.append(pad).append("  <#list ").append(listVar).append(" as item>\n");
                sb.append(pad).append("    {\n");
                writeChildren(sb, item, indent + 3);
                sb.append(pad).append("    }\n");
                sb.append(pad).append("    <#sep>,\n");
                sb.append(pad).append("  </#list>\n");
                sb.append(pad).append("]");
            }
        } else if (node.children != null) {
            // 对象
            sb.append("{\n");
            writeChildren(sb, node, indent + 1);
            sb.append(pad).append("}");
        }
    }

    /** 递归检查子树中所有叶子是否都是空占位符 */
    private boolean allEmpty(JsonNode node) {
        if (node.leafExpr != null) {
            return "\"\"".equals(node.leafExpr);
        }
        if (node.children != null) {
            for (JsonNode child : node.children.values()) {
                if (!allEmpty(child)) return false;
            }
            return true;
        }
        return true;
    }

    private void writeChildren(StringBuilder sb, JsonNode node, int indent) {
        String pad = "  ".repeat(indent);
        int count = 0, total = node.children.size();
        for (Map.Entry<String, JsonNode> e : node.children.entrySet()) {
            count++;
            String comma = count < total ? "," : "";
            String key = e.getKey();
            JsonNode child = e.getValue();

            if (child.leafExpr != null) {
                // 简单字段
                sb.append(pad).append("\"").append(key).append("\": ").append(child.leafExpr).append(comma).append("\n");
            } else {
                // 嵌套对象/数组 — 写 key: 后递归
                sb.append(pad).append("\"").append(key).append("\": ");
                writeNode(sb, key, child, indent);
                if (comma.equals(",")) sb.append(comma);
                sb.append("\n");
            }
        }
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
    private Long getOrCreateFlow(FlowDsl dsl, ProviderConfig cfg, String code, String type,
                                  Long pid, String interfaceId) throws Exception {
        // REPAY→REPAYMENT 规范化 (FundLink 侧 FlowType 枚举为 REPAYMENT)
        String fundLinkType = "REPAY".equalsIgnoreCase(type) ? "REPAYMENT" : (type != null ? type : "LOAN");
        String flowCode = fundLinkType + "_" + code;
        if (interfaceId != null && !interfaceId.isBlank()) {
            flowCode = flowCode + "_" + interfaceId;
        }
        Long existing = findFlowByCode(flowCode);
        if (existing != null) {
            log.info("[WRITE] Flow exists code={} id={}", flowCode, existing);
            return existing;
        }

        String templateCode = "AI_" + code;
        enrichFlowDsl(dsl, cfg, templateCode);
        Map<String, Object> graphData = Map.of("nodes", dsl.getNodes(), "edges",
                dsl.getEdges() != null ? dsl.getEdges() : Collections.emptyList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowCode", flowCode);
        body.put("flowName", code + " AI生成流程");
        body.put("flowType", fundLinkType);
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

    private void enrichFlowDsl(FlowDsl dsl, ProviderConfig cfg, String templateCode) {
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

            // Fix templateCode — LLM outputs placeholder "LOAN_REQ", real code is "AI_{providerCode}"
            if ("TEMPLATE_RENDER".equals(node.getType())) {
                @SuppressWarnings("unchecked")
                Map<String, Object> config = (Map<String, Object>) data.get("config");
                if (config == null) { config = new LinkedHashMap<>(); data.put("config", config); }
                config.put("templateCode", templateCode);
                log.info("[WRITE] TEMPLATE_RENDER templateCode fixed: {} → {}", config.get("templateCode"), templateCode);
            }

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

    @SuppressWarnings("unchecked")
    private Map<String, Object> put(String path, Object body) throws Exception {
        URI uri = new URI(fundlinkUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("PUT");
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
