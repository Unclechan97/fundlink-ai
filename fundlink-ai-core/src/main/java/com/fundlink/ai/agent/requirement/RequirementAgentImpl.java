package com.fundlink.ai.agent.requirement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.FlowTypeDetector;
import com.fundlink.ai.agent.PromptBuilder;
import com.fundlink.ai.agent.PromptEnhancer;
import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import com.fundlink.ai.gateway.RagGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import com.fundlink.ai.agent.diagnosis.DiagnosisResult;

import java.util.*;

@Slf4j
@Component
public class RequirementAgentImpl implements RequirementAgent {

    private static final Set<String> VALID_FLOW_TYPES = Set.of("LOAN", "CREDIT", "REPAY");

    private final LlmGateway llmGateway;
    private final PromptBuilder promptBuilder;
    private final PromptEnhancer enhancer;
    private final ObjectMapper json = new ObjectMapper();

    public RequirementAgentImpl(LlmGateway llmGateway, PromptBuilder promptBuilder,
                                 PromptEnhancer enhancer) {
        this.llmGateway = llmGateway;
        this.promptBuilder = promptBuilder;
        this.enhancer = enhancer;
    }

    @Override
    public RequirementResult analyze(String documentText, String providerCode, String flowType,
                                      List<DiagnosisResult> previousErrors) {
        String traceId = "req-" + UUID.randomUUID().toString().substring(0, 8);
        // 自动检测 flowType（用户未选定时）
        String detected = FlowTypeDetector.detect(documentText, flowType);
        String ft = detected.toLowerCase();
        boolean autoDetected = flowType == null || flowType.isBlank();
        log.info("[REQ-AGENT] Start  traceId={}  provider={}  flowType={}  autoDetected={}  docLen={}",
                traceId, providerCode, detected, autoDetected, documentText.length());

        // 1. RAG 检索历史案例 — 不可用时解析照常进行，但结果上携带用户可见提示
        RagGateway.SearchResult rag = enhancer.search(ft + " 字段映射 流程配置 " + providerCode);
        List<String> ragExamples = rag.getResults();
        String notice = null;
        if (!rag.isAvailable()) {
            log.warn("[REQ-AGENT] RAG 不可用  traceId={}", traceId);
            notice = "知识库暂不可用，本次解析未参考历史案例";
        }
        log.info("[REQ-AGENT] RAG examples={}  available={}  traceId={}", ragExamples.size(), rag.isAvailable(), traceId);

        // 2. 组装 Prompt (模板 + 字段目录 + RAG)
        String prompt = promptBuilder.build(documentText, providerCode, ft, ragExamples);

        // 2b. 注入上一轮诊断结果 (Retry 修正)
        if (previousErrors != null && !previousErrors.isEmpty()) {
            prompt = injectPreviousErrors(prompt, previousErrors);
        }

        log.info("[REQ-AGENT] Prompt built  traceId={}  len={}", traceId, prompt.length());

        // 3. 调 LLM
        LlmRequest request = LlmRequest.ofTask("requirement", prompt, traceId);
        LlmResponse response = llmGateway.chat(request);

        // 4. 安全解析
        RequirementResult result = parseSafely(response.getContent());
        if (notice != null) {
            result.setNotice(notice);
        }

        // 5. 字段完整性检查 — 接口文档中的所有字段都必须有映射
        if (result.getParseError() == null) {
            List<String> missing = FieldCompletenessGuard.missingFields(result);
            if (!missing.isEmpty()) {
                result.setParseError("字段缺少映射: " + String.join(", ", missing));
                log.error("[REQ-AGENT] Field completeness failed  traceId={}  missing={}",
                        traceId, missing);
            }
        }

        if (result.getParseError() != null) {
            log.error("[REQ-AGENT] Analysis completed with parse error  traceId={}  error={}",
                    traceId, result.getParseError());
        } else {
            log.info("[REQ-AGENT] Done  traceId={}  mappings={}  flowNodes={}  schemaFields={}",
                    traceId,
                    result.getFieldMappings() != null ? result.getFieldMappings().size() : 0,
                    countNodes(result), countFields(result));
        }
        return result;
    }

    private int countNodes(RequirementResult r) {
        var dsl = r.getFlowDsl();
        return (dsl != null && dsl.getNodes() != null) ? dsl.getNodes().size() : 0;
    }

    private int countFields(RequirementResult r) {
        var s = r.getInterfaceSchema();
        return (s != null && s.getFields() != null) ? s.getFields().size() : 0;
    }

    /** 注入上一轮诊断结果到 Prompt — design §3.1 */
    private String injectPreviousErrors(String prompt, List<DiagnosisResult> errors) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 上一轮验证失败，请修正:\n");
        for (int i = 0; i < errors.size(); i++) {
            DiagnosisResult e = errors.get(i);
            sb.append("- 阶段: ").append(e.getPhase() != null ? e.getPhase() : "UNKNOWN");
            if (e.getRootCause() != null) {
                sb.append(", 诊断: ").append(e.getRootCause());
            }
            if (e.getFixSuggestion() != null) {
                sb.append("\n  建议: ").append(e.getFixSuggestion());
            }
            sb.append("\n");
        }
        sb.append("\n请基于建议修正，重新输出完整 JSON。\n\n");
        sb.append(prompt);
        return sb.toString();
    }

    /** 使用 JsonNode 安全解析，字段缺失不抛异常。JSON 完全不可解析时设置 parseError */
    private RequirementResult parseSafely(String content) {
        RequirementResult result = new RequirementResult();
        result.setFieldMappings(new ArrayList<>());

        if (content == null || content.isBlank()) {
            result.setParseError("LLM returned empty response");
            log.error("[REQ-AGENT] Parse failed: empty response from LLM");
            return result;
        }

        try {
            // 提取 JSON 块
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

            // flow_type — LLM 解析阶段自动识别
            String ftOut = root.path("flow_type").asText(null);
            if (ftOut != null && !ftOut.isBlank()) {
                String normalized = ftOut.toUpperCase().trim();
                result.setFlowType(VALID_FLOW_TYPES.contains(normalized) ? normalized : null);
            }

            // provider_config
            JsonNode pc = root.path("provider_config");
            if (!pc.isMissingNode()) {
                var cfg = new ProviderConfig();
                cfg.setProviderName(pc.path("providerName").asText(null));
                cfg.setBaseUrl(pc.path("baseUrl").asText(null));
                result.setProviderConfig(cfg);
            }

            // interface_schema
            JsonNode schema = root.path("interface_schema");
            if (!schema.isMissingNode()) {
                var is = new InterfaceSchema();
                is.setEndpoint(schema.path("endpoint").asText(null));
                is.setMethod(schema.path("method").asText(null));
                JsonNode fieldsNode = schema.path("fields");
                if (fieldsNode.isArray()) {
                    List<InterfaceField> fields = new ArrayList<>();
                    for (JsonNode f : fieldsNode) {
                        var fi = new InterfaceField();
                        fi.setName(f.path("name").asText(null));
                        fi.setType(f.path("type").asText(null));
                        fi.setRequired(f.path("required").asBoolean(false));
                        fi.setDescription(f.path("description").asText(null));
                        fields.add(fi);
                    }
                    is.setFields(fields);
                }
                result.setInterfaceSchema(is);
            }

            // field_mappings
            JsonNode mappings = root.path("field_mappings");
            if (mappings.isArray()) {
                List<FieldMappingSuggestion> list = new ArrayList<>();
                for (JsonNode m : mappings) {
                    var fm = new FieldMappingSuggestion();
                    String fundField = m.path("fund_field").asText(null);
                    String sourcePath = m.path("source_path").asText(null);
                    String transform = m.path("transform").asText(null);
                    // 防 LLM 输出字符串 "null" — Jackson 对此返回 Java 字符串 "null" 而非 null
                    if (sourcePath != null && "null".equalsIgnoreCase(sourcePath.trim())) sourcePath = null;
                    if (transform != null && "null".equalsIgnoreCase(transform.trim())) transform = null;
                    fm.setFundField(fundField);
                    // 找不到数据源时为 "" (正常情况，Template 中用 "" 占位)
                    fm.setSourcePath(sourcePath != null ? sourcePath : "");
                    fm.setTransform(transform);
                    fm.setConfidence(m.path("confidence").asDouble(0.8));
                    fm.setRemark(m.path("remark").asText(null));
                    // 跳过只有 fundField 为空的无效映射（sourcePath 为空是合法的 TODO 行）
                    if (fundField == null || fundField.isBlank()) {
                        log.warn("[REQ-AGENT] Skipping mapping with blank fundField");
                        continue;
                    }
                    // sourcePath 为空时跳过格式校验（标记字段，无需校验）
                    if (sourcePath != null && !sourcePath.isBlank()) {
                        // 检测 sourcePath 截断 (project_status 6.7)
                        if (!sourcePath.contains(".") && !sourcePath.matches("^[a-zA-Z]+$")) {
                            log.warn("[REQ-AGENT] Suspected truncated sourcePath: '{}' (fundField={})",
                                    sourcePath, fundField);
                        }
                        // 校验 sourcePath 仅含合法字符
                        if (!sourcePath.matches("^[a-zA-Z][a-zA-Z0-9]*(\\.[a-zA-Z][a-zA-Z0-9]*)*$")) {
                            log.warn("[REQ-AGENT] Invalid sourcePath format: '{}' (fundField={})",
                                    sourcePath, fundField);
                        }
                    }
                    list.add(fm);
                }
                result.setFieldMappings(list);
            }

            // flow_dsl
            JsonNode dsl = root.path("flow_dsl");
            if (!dsl.isMissingNode()) {
                var fd = new FlowDsl();
                JsonNode nodesNode = dsl.path("nodes");
                if (nodesNode.isArray()) {
                    List<FlowNode> nodes = new ArrayList<>();
                    for (JsonNode n : nodesNode) {
                        var fn = new FlowNode();
                        fn.setId(n.path("id").asText(null));
                        fn.setType(n.path("type").asText(null));
                        // 保留 data 字段 (label + config)
                        if (!n.path("data").isMissingNode()) {
                            fn.setData(json.convertValue(n.path("data"), Map.class));
                        }
                        nodes.add(fn);
                    }
                    fd.setNodes(nodes);
                }
                JsonNode edgesNode = dsl.path("edges");
                if (edgesNode.isArray()) {
                    List<FlowEdge> edges = new ArrayList<>();
                    for (JsonNode e : edgesNode) {
                        var fe = new FlowEdge();
                        fe.setId(e.path("id").asText(null));
                        fe.setSource(e.path("source").asText(null));
                        fe.setTarget(e.path("target").asText(null));
                        fe.setLabel(e.path("label").asText(null));
                        fe.setConditionExpr(e.path("conditionExpr").asText(null));
                        edges.add(fe);
                    }
                    fd.setEdges(edges);
                }
                result.setFlowDsl(fd);
            }
        } catch (Exception e) {
            log.error("[REQ-AGENT] Parse failed: {}", e.getMessage());
            log.debug("[REQ-AGENT] Raw content:\n{}", content);
            result.setParseError("LLM response parse failed: " + e.getMessage());
        }
        return result;
    }
}
