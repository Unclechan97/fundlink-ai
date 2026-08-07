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

        log.info("[REQ-AGENT] Done  traceId={}  mappings={}  flowNodes={}  schemaFields={}",
                traceId,
                result.getFieldMappings() != null ? result.getFieldMappings().size() : 0,
                countNodes(result), countFields(result));
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

    /** 使用 JsonNode 安全解析，字段缺失不抛异常 */
    private RequirementResult parseSafely(String content) {
        RequirementResult result = new RequirementResult();
        result.setFieldMappings(new ArrayList<>());

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
                    fm.setFundField(m.path("fund_field").asText(null));
                    fm.setSourcePath(m.path("source_path").asText(null));
                    fm.setTransform(m.path("transform").asText(null));
                    fm.setConfidence(m.path("confidence").asDouble(0.8));
                    if (fm.getFundField() != null) list.add(fm);
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
                        edges.add(fe);
                    }
                    fd.setEdges(edges);
                }
                result.setFlowDsl(fd);
            }
        } catch (Exception e) {
            log.error("[REQ-AGENT] Parse failed: {}", e.getMessage());
            log.debug("[REQ-AGENT] Raw content (first 500):\n{}",
                    content.length() > 500 ? content.substring(0, 500) : content);
        }
        return result;
    }
}
