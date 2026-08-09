package com.fundlink.ai.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.requirement.FieldMappingSuggestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 模板渲染验证器 — 调 FundLink Preview API + 验证结果 (设计 §3.2)
 */
@Slf4j
@Service
public class TemplateValidator {

    private final String fundlinkUrl;
    private final ObjectMapper json = new ObjectMapper();

    public TemplateValidator(@Value("${fundlink.admin.base-url:http://localhost:8080}") String fundlinkUrl) {
        this.fundlinkUrl = fundlinkUrl;
    }

    /**
     * 调 FundLink 模板预览 API 验证 FreeMarker 渲染结果
     *
     * @param templateId    模板 ID
     * @param previewData   预览数据 (from TestGenResult)
     * @param fieldMappings 字段映射 (验证所有 fundField 出现在渲染结果中)
     */
    public ValidationResult validate(Long templateId, Map<String, Object> previewData,
                                      List<FieldMappingSuggestion> fieldMappings) {
        try {
            // 0. 护盾: 如果 LLM 返回了数组类型的数据源，取第一个元素
            Map<String, Object> safeData = new LinkedHashMap<>();
            if (previewData != null) {
                for (Map.Entry<String, Object> e : previewData.entrySet()) {
                    Object v = e.getValue();
                    if (v instanceof List && !((List) v).isEmpty()) {
                        log.warn("[VALIDATOR] Data source '{}' is array, taking first element", e.getKey());
                        safeData.put(e.getKey(), ((List) v).get(0));
                    } else {
                        safeData.put(e.getKey(), v);
                    }
                }
            }

            // 1. 调 Preview API
            String safeJson = json.writeValueAsString(safeData);
            String body = json.writeValueAsString(
                    java.util.Map.of("testData", safeJson));
            String previewUrl = fundlinkUrl + "/api/admin/templates/" + templateId + "/preview";

            log.info("[VALIDATE] >>> PREVIEW  templateId={}  dataSize={}  previewDataKeys={}",
                    templateId, safeJson.length(), safeData.keySet());
            log.info("[VALIDATE] >>> BODY  {}", safeJson.replaceAll("\\s+", " "));

            String resp = postJson(previewUrl, body);

            if (resp == null || resp.isBlank()) {
                log.warn("[VALIDATE] <<< PREVIEW EMPTY");
                return ValidationResult.fail(Collections.emptyList(), "Preview API returned empty response", "");
            }

            log.info("[VALIDATE] <<< PREVIEW  respLen={}", resp.length());
            log.info("[VALIDATE] <<< RAW  {}", resp.replaceAll("\\s+", " "));

            Map<String, Object> root = json.readValue(resp, Map.class);
            Object code = root.get("code");
            if (code instanceof Number && ((Number) code).intValue() != 0) {
                log.error("[VALIDATE] <<< PREVIEW ERROR  code={}  msg={}", code, root.get("msg"));
                return ValidationResult.fail(Collections.emptyList(),
                        "Preview API error code=" + code + " msg=" + root.get("msg"), resp);
            }

            Object data = root.get("data");
            String renderResult = null;
            if (data instanceof Map) {
                Object rr = ((Map) data).get("renderResult");
                renderResult = rr != null ? rr.toString() : null;
            }

            if (renderResult == null || renderResult.isBlank()) {
                log.warn("[VALIDATE] <<< RENDER EMPTY");
                return ValidationResult.fail(Collections.emptyList(), "Render result is empty", resp);
            }

            log.info("[VALIDATE] <<< RENDER  len={}  preview={}", renderResult.length(),
                    renderResult.length() > 500 ? renderResult.substring(0, 500) + "..." : renderResult);

            // 2. 检查 50002
            if (renderResult.contains("50002")) {
                return ValidationResult.fail(Collections.emptyList(),
                        "Template render error code 50002", renderResult);
            }

            // 3. 检查有 sourcePath 的字段存在于渲染结果
            // 空 sourcePath 的字段是 TODO 占位，不检查（空数组中不会展开）
            List<String> missingFields = new ArrayList<>();
            if (fieldMappings != null) {
                for (FieldMappingSuggestion m : fieldMappings) {
                    String fundField = m.getFundField();
                    if (fundField == null) continue;
                    // 占位字段 — 跳过检查（模板中可能为空数组或被省略）
                    if (m.getSourcePath() == null || m.getSourcePath().isBlank()) continue;
                    // 提取叶子名: "repayAccount.bankCode" → "bankCode"; "repayPeriods[].periodNo" → "periodNo"
                    String leafName = fundField.contains(".")
                            ? fundField.substring(fundField.lastIndexOf('.') + 1)
                            : fundField;
                    leafName = leafName.replace("[]", "");
                    if (!renderResult.contains("\"" + leafName + "\"")) {
                        missingFields.add(fundField);
                    }
                }
            }

            if (!missingFields.isEmpty()) {
                log.warn("[VALIDATE] MISSING FIELDS: {}  expected={}  checked={}",
                        String.join(", ", missingFields),
                        fieldMappings.stream().map(FieldMappingSuggestion::getFundField).toList(),
                        missingFields.size());
                return ValidationResult.fail(missingFields,
                        "Missing fields in render output: " + String.join(", ", missingFields), renderResult);
            }
            log.info("[VALIDATE] All {} fields present in render output", fieldMappings != null ? fieldMappings.size() : 0);

            return ValidationResult.ok(renderResult);

        } catch (Exception e) {
            log.error("[VALIDATOR] Preview failed: {}", e.getMessage());
            return ValidationResult.fail(Collections.emptyList(), "Exception: " + e.getMessage(), null);
        }
    }

    private String postJson(String url, String body) throws Exception {
        URI uri = new URI(url);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        if (conn.getResponseCode() >= 400) {
            String err = new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            log.warn("[VALIDATOR] Preview HTTP {}: {}", conn.getResponseCode(), err);
            return null;
        }

        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    // -- result class --

    public static class ValidationResult {
        private boolean success;
        private List<String> missingFields;
        private String errorMsg;
        private String renderResult;

        public static ValidationResult ok(String renderResult) {
            ValidationResult r = new ValidationResult();
            r.success = true;
            r.missingFields = Collections.emptyList();
            r.renderResult = renderResult;
            return r;
        }

        public static ValidationResult fail(List<String> missingFields, String errorMsg, String raw) {
            ValidationResult r = new ValidationResult();
            r.success = false;
            r.missingFields = missingFields;
            r.errorMsg = errorMsg;
            r.renderResult = raw;
            return r;
        }

        public boolean isSuccess() { return success; }
        public List<String> getMissingFields() { return missingFields; }
        public String getErrorMsg() { return errorMsg; }
        public String getRenderResult() { return renderResult; }
    }
}
