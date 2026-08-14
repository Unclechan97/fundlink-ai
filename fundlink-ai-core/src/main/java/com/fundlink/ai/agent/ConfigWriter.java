package com.fundlink.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.requirement.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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

            // Mappings: 逐条 upsert（幂等且原子）— 先删后建中间崩溃会导致零 mapping
            if (templateId != null && result.getFieldMappings() != null) {
                int count = upsertFieldMappings(templateId, result.getFieldMappings());
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

    // ── Provider（幂等：先查后建；并发冲突降级为复用）──
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
        try {
            Map<String, Object> resp = post("/api/admin/providers", body);
            return extractId(resp);
        } catch (Exception e) {
            // TOCTOU 降级：并发下另一 loop 已创建同名 provider → 重新查询复用
            Long reused = findProviderByCode(code);
            if (reused != null) {
                log.info("[WRITE] Provider {} created concurrently — reuse id={}", code, reused);
                return reused;
            }
            throw e;
        }
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

        try {
            Map<String, Object> resp = post("/api/admin/templates", body);
            return extractId(resp);
        } catch (Exception e) {
            // TOCTOU 降级：并发下另一 loop 已创建同名 template → 复用并更新 content（最后写入为准）
            Long reused = findTemplateByCode(templateCode);
            if (reused != null) {
                log.info("[WRITE] Template {} created concurrently — reuse id={} and update content", templateCode, reused);
                put("/api/admin/templates/" + reused, body);
                return reused;
            }
            throw e;
        }
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

    // ── FieldMapping ──
    /**
     * 逐条 upsert（幂等）：先 GET 现有 mappings 按 fundField 建索引，
     * 存在 → PUT 更新，不存在 → POST 新建。
     * 不再先删后建 — 中间崩溃不再导致零 mapping，并发写入以最后写入为准。
     */
    private int upsertFieldMappings(Long templateId, List<FieldMappingSuggestion> mappings) throws Exception {
        String path = "/api/admin/templates/" + templateId + "/mappings";

        // 现有 mapping 索引：fundField → id
        Map<String, Object> existingIdByFundField = new LinkedHashMap<>();
        try {
            String resp = get(path);
            if (resp != null && !resp.isBlank()) {
                Map<String, Object> root = json.readValue(resp, Map.class);
                Object data = root.get("data");
                if (data instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map> existing = (List<Map>) data;
                    for (Map item : existing) {
                        Object ff = item.get("fundField");
                        Object id = item.get("id");
                        if (ff != null && id != null) {
                            existingIdByFundField.put(String.valueOf(ff), id);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // GET 失败不中断写入 — 与旧行为一致（旧代码此处仅 log）。
            // FundLink 不可用时后续 POST/PUT 同样会失败并计入 WriteResult。
            log.warn("[WRITE] Failed to read existing mappings for template {}: {}", templateId, e.getMessage());
        }

        int sort = 0;
        for (FieldMappingSuggestion m : mappings) {
            if (m.getFundField() == null || m.getFundField().isBlank()) continue;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fundField", m.getFundField());
            body.put("sourcePath", m.getSourcePath());
            body.put("transform", m.getTransform());
            body.put("sortOrder", sort);
            Object existingId = existingIdByFundField.get(m.getFundField());
            if (existingId != null) {
                put(path + "/" + existingId, body);
            } else {
                post(path, body);
                existingIdByFundField.put(m.getFundField(), m.getFundField()); // 防同批次重复 fundField 二次 POST
            }
            sort++;
        }
        return sort;
    }

    // ── Flow（幂等：先查后建；存在时 PUT 更新 graphData）──
    private Long getOrCreateFlow(FlowDsl dsl, ProviderConfig cfg, String code, String type,
                                  Long pid, String interfaceId) throws Exception {
        // REPAY→REPAYMENT 规范化 (FundLink 侧 FlowType 枚举为 REPAYMENT)
        String fundLinkType = "REPAY".equalsIgnoreCase(type) ? "REPAYMENT" : (type != null ? type : "LOAN");
        String flowCode = fundLinkType + "_" + code;
        if (interfaceId != null && !interfaceId.isBlank()) {
            flowCode = flowCode + "_" + interfaceId;
        }

        String templateCode = "AI_" + code;
        if (interfaceId != null && !interfaceId.isBlank()) {
            templateCode = templateCode + "_" + interfaceId;
        }
        enrichFlowDsl(dsl, cfg, templateCode);
        Map<String, Object> graphData = Map.of("nodes", dsl.getNodes(), "edges",
                dsl.getEdges() != null ? dsl.getEdges() : Collections.emptyList());

        Long existing = findFlowByCode(flowCode);
        if (existing != null) {
            // 存在时 PUT 更新 graphData — 否则 EDIT_AND_RETRY 修正后的配置从未生效
            log.info("[WRITE] Flow exists code={} id={} — updating graphData", flowCode, existing);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("flowCode", flowCode);
            body.put("flowName", code + " AI生成流程");
            body.put("flowType", fundLinkType);
            body.put("providerId", pid);
            body.put("graphData", json.writeValueAsString(graphData));
            put("/api/admin/flows/" + existing, body);
            return existing;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("flowCode", flowCode);
        body.put("flowName", code + " AI生成流程");
        body.put("flowType", fundLinkType);
        body.put("providerId", pid);
        body.put("graphData", json.writeValueAsString(graphData));
        try {
            Map<String, Object> resp = post("/api/admin/flows", body);
            return extractId(resp);
        } catch (Exception e) {
            // TOCTOU 降级：并发下另一 loop 已创建同名 flow → 复用并更新 graphData（最后写入为准）
            Long reused = findFlowByCode(flowCode);
            if (reused != null) {
                log.info("[WRITE] Flow {} created concurrently — reuse id={} and update graphData", flowCode, reused);
                put("/api/admin/flows/" + reused, body);
                return reused;
            }
            throw e;
        }
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

    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 50;   // 防御上限：最多翻 50 页（1 万条）

    /** 全量翻页拉取 — 单页 size=200 会在超 200 条时漏数据导致必然重复创建 */
    @SuppressWarnings("unchecked")
    private List<Map> listAll(String path) throws Exception {
        List<Map> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String resp = get(path + "?page=" + page + "&size=" + PAGE_SIZE);
            if (resp == null || resp.isBlank()) break;
            Map<String, Object> root = json.readValue(resp, Map.class);
            Object data = root.get("data");
            List<Map> records = null;
            Integer total = null;
            if (data instanceof Map) {
                Object recordsObj = ((Map) data).get("records");
                if (recordsObj instanceof List) records = (List<Map>) recordsObj;
                Object totalObj = ((Map) data).get("total");
                if (totalObj instanceof Number) total = ((Number) totalObj).intValue();
            } else if (data instanceof List) {
                records = (List<Map>) data;
            }
            if (records == null || records.isEmpty()) break;
            all.addAll(records);
            if (total != null && all.size() >= total) break;   // 有 total 按总数收敛
            if (records.size() < PAGE_SIZE) break;             // 无 total 按不满页收敛
        }
        if (all.size() >= MAX_PAGES * PAGE_SIZE) {
            log.warn("[WRITE] listAll {} reached page cap {} — 结果可能被截断", path, MAX_PAGES);
        }
        return all;
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
        return readResponse(conn, path);
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
        return readResponse(conn, path);
    }

    /**
     * 统一响应检查：
     * <ul>
     *   <li>HTTP 非 2xx → 抛错（getErrorStream 判空兜底，错误路径不再 NPE）</li>
     *   <li>HTTP 200 但业务码 code != 0（如"已存在"）→ 抛错，触发调用方的 TOCTOU 降级复用</li>
     * </ul>
     */
    private Map<String, Object> readResponse(HttpURLConnection conn, String path) throws Exception {
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream es = conn.getErrorStream();
            String err = es != null
                    ? new String(es.readAllBytes(), StandardCharsets.UTF_8)
                    : "(无错误响应体, HTTP " + code + ")";
            throw new RuntimeException("FundLink API error " + code + " on " + path + ": " + err);
        }
        Map<String, Object> resp = json.readValue(conn.getInputStream(), Map.class);
        Object biz = resp.get("code");
        if (biz instanceof Number && ((Number) biz).intValue() != 0) {
            throw new RuntimeException("FundLink API business error on " + path + ": " + resp);
        }
        return resp;
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
