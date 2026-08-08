package com.fundlink.ai.agent.requirement;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.PromptBuilder;
import com.fundlink.ai.agent.PromptEnhancer;
import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class RequirementAgentImpl implements RequirementAgent {

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
    public RequirementResult analyze(String documentText, String providerCode) {
        String traceId = "req-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[REQ-AGENT] Start  traceId={}  provider={}  docLen={}",
                traceId, providerCode, documentText.length());

        // 1. RAG 检索历史案例
        List<String> ragExamples = enhancer.search("字段映射 流程配置 " + providerCode);
        log.info("[REQ-AGENT] RAG examples={}  traceId={}", ragExamples.size(), traceId);

        // 2. 组装 Prompt (模板 + 字段目录 + RAG)
        String prompt = promptBuilder.build(documentText, providerCode, "loan", ragExamples);
        log.info("[REQ-AGENT] Prompt built  traceId={}  len={}", traceId, prompt.length());

        // 3. 调 LLM
        LlmRequest request = LlmRequest.of("qwen", "qwen-plus", prompt, traceId);
        LlmResponse response = llmGateway.chat(request);

        // 4. 安全解析
        RequirementResult result = parseSafely(response.getContent());

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
                    fm.setFundField(fundField);
                    fm.setSourcePath(sourcePath);
                    fm.setTransform(m.path("transform").asText(null));
                    fm.setConfidence(m.path("confidence").asDouble(0.8));
                    // 跳过无效映射
                    if (fundField == null || sourcePath == null) {
                        log.warn("[REQ-AGENT] Skipping mapping with null field: fundField={} sourcePath={}",
                                fundField, sourcePath);
                        continue;
                    }
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
