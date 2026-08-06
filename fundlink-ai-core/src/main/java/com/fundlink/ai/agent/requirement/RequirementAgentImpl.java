package com.fundlink.ai.agent.requirement;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RequirementAgentImpl implements RequirementAgent {

    private final LlmGateway llmGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public RequirementResult analyze(String documentText, String providerCode) {
        String traceId = "req-" + UUID.randomUUID().toString().substring(0, 8);
        String prompt = buildPrompt(documentText, providerCode);
        LlmRequest request = LlmRequest.of("deepseek", "deepseek-chat", prompt, traceId);
        LlmResponse response = llmGateway.chat(request);
        return parseResponse(response.getContent());
    }

    private String buildPrompt(String doc, String code) {
        return "分析接口文档输出JSON: {\"interface_schema\":{...},\"field_mappings\":[...],\"flow_dsl\":{...}}。映射规则: amount→loanInfo.amount(transform=formatAmount), customerName→userInfo.realName, idType→userInfo.idType(transform=enumMap), loanNo→loanInfo.loanNo。文档: " + doc;
    }

    @SuppressWarnings("unchecked")
    private RequirementResult parseResponse(String jsonContent) {
        RequirementResult result = new RequirementResult();
        try {
            String json = jsonContent;
            if (json.contains("```json")) {
                json = json.substring(json.indexOf("```json") + 7);
                if (json.contains("```")) json = json.substring(0, json.lastIndexOf("```"));
            }
            Map<String, Object> parsed = objectMapper.readValue(
                    json, new TypeReference<Map<String, Object>>() {});

            Map<String, Object> sm = (Map<String, Object>) parsed.get("interface_schema");
            if (sm != null) {
                InterfaceSchema schema = new InterfaceSchema();
                schema.setEndpoint((String) sm.get("endpoint"));
                List<Map<String, Object>> fl = (List<Map<String, Object>>) sm.get("fields");
                if (fl != null) {
                    List<InterfaceField> fields = new ArrayList<>();
                    for (var f : fl) {
                        InterfaceField field = new InterfaceField();
                        field.setName((String) f.get("name"));
                        field.setType((String) f.get("type"));
                        field.setRequired(Boolean.TRUE.equals(f.get("required")));
                        fields.add(field);
                    }
                    schema.setFields(fields);
                }
                result.setInterfaceSchema(schema);
            }

            List<Map<String, Object>> ml = (List<Map<String, Object>>) parsed.get("field_mappings");
            if (ml != null) {
                List<FieldMappingSuggestion> mappings = new ArrayList<>();
                for (var m : ml) {
                    FieldMappingSuggestion fm = new FieldMappingSuggestion();
                    fm.setFundField((String) m.get("fund_field"));
                    fm.setSourcePath((String) m.get("source_path"));
                    fm.setTransform((String) m.get("transform"));
                    Object c = m.get("confidence");
                    fm.setConfidence(c instanceof Number ? ((Number) c).doubleValue() : 0.8);
                    mappings.add(fm);
                }
                result.setFieldMappings(mappings);
            }

            Map<String, Object> dm = (Map<String, Object>) parsed.get("flow_dsl");
            if (dm != null) {
                FlowDsl dsl = new FlowDsl();
                List<Map<String, Object>> nl = (List<Map<String, Object>>) dm.get("nodes");
                if (nl != null) {
                    List<FlowNode> nodes = new ArrayList<>();
                    for (var n : nl) {
                        FlowNode node = new FlowNode();
                        node.setId((String) n.get("id"));
                        node.setType((String) n.get("type"));
                        nodes.add(node);
                    }
                    dsl.setNodes(nodes);
                }
                List<Map<String, Object>> el = (List<Map<String, Object>>) dm.get("edges");
                if (el != null) {
                    List<FlowEdge> edges = new ArrayList<>();
                    for (var e : el) {
                        FlowEdge edge = new FlowEdge();
                        edge.setId((String) e.get("id"));
                        edge.setSource((String) e.get("source"));
                        edge.setTarget((String) e.get("target"));
                        edges.add(edge);
                    }
                    dsl.setEdges(edges);
                }
                result.setFlowDsl(dsl);
            }
        } catch (Exception e) {
            log.error("Parse LLM response failed", e);
        }
        return result;
    }
}
