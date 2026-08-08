package com.fundlink.ai.agent.diagnosis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 诊断 Agent — 规则引擎 pre-filter + LLM 深度诊断 (双层)
 * <p>
 * 规则引擎覆盖: FreeMarker 变量缺失 / SpEL 语法 / 数据源超时 / enumMap 空值
 * LLM 深度诊断: 规则覆盖不到时，结构化 JSON 输出
 */
@Slf4j
@Service
public class DiagnosisAgentImpl implements DiagnosisAgent {

    private final LlmGateway llmGateway;
    private final ObjectMapper json = new ObjectMapper();

    public DiagnosisAgentImpl(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public DiagnosisResult diagnose(String phase, String errorDescription,
                                     Map<String, Object> context) {
        log.info("[DIAG] Start  phase={}  errorLen={}", phase,
                errorDescription != null ? errorDescription.length() : 0);

        // 1. 规则引擎 pre-filter
        DiagnosisResult result = ruleBasedDiagnose(phase, errorDescription);

        // 2. LLM 深度诊断 — 规则覆盖不到或置信度不够
        if (result.getConfidence() < 0.7) {
            log.info("[DIAG] Rule confidence low ({}) → LLM deep diagnosis  phase={}",
                    result.getConfidence(), phase);
            DiagnosisResult llmResult = llmBasedDiagnose(phase, errorDescription, context);
            if (llmResult.getConfidence() > result.getConfidence()) {
                result = llmResult;
            }
        }

        log.info("[DIAG] Done  phase={}  confidence={}  rootCause={}",
                phase, result.getConfidence(),
                result.getRootCause() != null ? result.getRootCause().substring(0,
                        Math.min(60, result.getRootCause().length())) : "null");

        return result;
    }

    /** Rule engine — design §3.4 */
    private DiagnosisResult ruleBasedDiagnose(String phase, String error) {
        DiagnosisResult r = new DiagnosisResult();
        r.setPhase(phase);
        List<String> chain = new ArrayList<>();

        if (error == null) {
            r.setConfidence(0.3);
            r.setRootCause("无错误信息");
            return r;
        }

        // chain-error detection
        if (error.contains("→")) {
            for (String step : error.split("→")) {
                chain.add(step.trim());
            }
        }
        r.setCauseChain(chain);

        // Rule: FreeMarker undefined variable — pattern: "undefined variable xxx"
        java.util.regex.Matcher fmMatcher = java.util.regex.Pattern
                .compile("undefined variable[:\s]+(\\S+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(error);
        boolean hasFmMatch = fmMatcher.find();
        if (hasFmMatch || error.contains("FreeMarker") || error.contains("模板渲染")
                || error.contains("50002")) {
            chain.add("FreeMarker 模板渲染失败");
            if (hasFmMatch) {
                String varName = fmMatcher.group(1);
                chain.add("未定义变量: " + varName);
                r.setRootCause("FreeMarker 变量未定义: " + varName + " — 可能 sourcePath 截断或字段映射缺失");
                r.setFixSuggestion("检查字段映射表中是否有对应的 fundField → sourcePath 映射，确认 sourcePath 完整（如 userInfo.realName 而非 userInfo.realNam）");
            } else if (error.contains("50002")) {
                r.setRootCause("模板渲染返回错误码 50002 — FreeMarker 引擎异常");
                r.setFixSuggestion("检查模板 content 字段的 FreeMarker 语法，特别关注 ${...} 中的变量路径是否存在");
            } else {
                r.setRootCause("FreeMarker 模板渲染错误：变量或语法异常");
                r.setFixSuggestion("使用模板预览接口 /api/admin/templates/{id}/preview 验证模板");
            }
            r.setConfidence(0.85);
            return r;
        }

        // Rule: SpEL parse error
        if (error.contains("SpEL") || error.contains("SpelParseException")
                || error.contains("EL1001E") || error.contains("CONDITION")) {
            chain.add("条件表达式 (SpEL) 解析异常");
            r.setRootCause("CONDITION 节点的 SpEL 表达式语法错误或引用的变量不存在");
            r.setFixSuggestion("检查流程 CONDITION 节点的 expression 配置，确认变量路径以 #root 开头格式正确");
            r.setConfidence(0.82);
            return r;
        }

        // Rule: Data source timeout / error
        if (error.contains("数据源") || error.contains("DATA_COLLECT")
                || error.contains("超时") || error.contains("timeout")) {
            chain.add("数据收集节点执行异常");
            r.setRootCause("数据源调用异常：目标服务不可用或响应超时");
            r.setFixSuggestion("1. 检查 Mock 平台是否启用 2. 检查数据源真实 URL 连通性 3. 增加超时配置");
            r.setConfidence(0.80);
            return r;
        }

        // Rule: enumMap null
        if (error.contains("enumMap") || error.contains("枚举")) {
            chain.add("枚举映射异常");
            r.setRootCause("enumMap 函数接收到空值或未注册的枚举类型");
            r.setFixSuggestion("检查 enumMap 调用参数和 fl_enum_mapping 表配置");
            r.setConfidence(0.78);
            return r;
        }

        // Fallback to LLM
        r.setConfidence(0.3);
        return r;
    }

    /** LLM deep diagnosis — parse structured JSON */
    private DiagnosisResult llmBasedDiagnose(String phase, String error,
                                              Map<String, Object> context) {
        DiagnosisResult r = new DiagnosisResult();
        r.setPhase(phase);
        try {
            String prompt = buildDiagnosisPrompt(phase, error, context);
            String traceId = "diag-" + UUID.randomUUID().toString().substring(0, 8);
            LlmRequest request = LlmRequest.ofTask("diagnosis", prompt, traceId);
            String content = llmGateway.chat(request).getContent();

            DiagnosisResult parsed = parseDiagnosisJson(content);
            if (parsed != null) return parsed;

            // Parse failed — fallback
            List<String> chain = new ArrayList<>();
            chain.add("错误信息: " + error.substring(0, Math.min(80, error.length())));
            r.setCauseChain(chain);
            r.setRootCause("需要进一步排查: " + error.substring(0, Math.min(60, error.length())));
            r.setFixSuggestion("建议查看流程实例详情和 API 调用日志");
            r.setConfidence(0.6);
        } catch (Exception e) {
            log.error("[DIAG] LLM diagnosis failed", e);
            r.setRootCause("诊断服务异常");
            r.setFixSuggestion("请人工排查");
            r.setConfidence(0.1);
        }
        return r;
    }

    private String buildDiagnosisPrompt(String phase, String error,
                                         Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是资金接入系统故障诊断专家。分析以下验证失败原因并给出修正建议。\n\n");
        sb.append("失败阶段: ").append(phase).append("\n");
        sb.append("错误信息: ").append(error).append("\n");

        if (context != null) {
            sb.append("\n## 上下文\n");
            try {
                sb.append("```json\n").append(json.writeValueAsString(context)).append("\n```\n");
            } catch (Exception e) {
                sb.append(context).append("\n");
            }
        }

        sb.append("\n严格按以下 JSON 格式输出（不要输出额外内容）:\n");
        sb.append("""
            ```json
            {
              "root_cause": "根因描述",
              "cause_chain": ["步骤1", "步骤2"],
              "fix_suggestion": "修正建议（可注入 RequirementAgent 的 Prompt）",
              "confidence": 0.85
            }
            ```
            """);
        return sb.toString();
    }

    /** Parse LLM diagnosis JSON output */
    private DiagnosisResult parseDiagnosisJson(String content) {
        if (content == null || content.isBlank()) return null;
        try {
            String raw = content;
            int start = raw.indexOf("```json");
            if (start >= 0) {
                start += 7;
                int end = raw.indexOf("```", start);
                if (end > start) raw = raw.substring(start, end);
            } else if (raw.contains("{")) {
                raw = raw.substring(raw.indexOf("{"));
                if (raw.lastIndexOf("}") > 0) raw = raw.substring(0, raw.lastIndexOf("}") + 1);
            }

            JsonNode root = json.readTree(raw);

            String rootCause = root.path("root_cause").asText(null);
            if (rootCause == null || rootCause.isBlank()) {
                return null; // LLM didn't return valid diagnosis → fall back to rule-based
            }

            DiagnosisResult r = new DiagnosisResult();
            r.setRootCause(rootCause);

            JsonNode chain = root.path("cause_chain");
            if (chain.isArray()) {
                List<String> chainList = new ArrayList<>();
                for (JsonNode c : chain) chainList.add(c.asText());
                r.setCauseChain(chainList);
            }

            r.setFixSuggestion(root.path("fix_suggestion").asText(null));
            r.setConfidence(root.path("confidence").asDouble(0.7));

            JsonNode corrected = root.path("corrected_config");
            if (!corrected.isMissingNode() && corrected.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cm = json.convertValue(corrected, Map.class);
                r.setCorrectedConfig(cm);
            }

            return r;
        } catch (Exception e) {
            log.warn("[DIAG] Failed to parse LLM diagnosis JSON: {}", e.getMessage());
            return null;
        }
    }
}
