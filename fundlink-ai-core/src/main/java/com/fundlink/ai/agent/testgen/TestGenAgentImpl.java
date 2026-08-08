package com.fundlink.ai.agent.testgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.requirement.*;
import com.fundlink.ai.gateway.LlmGateway;
import com.fundlink.ai.gateway.LlmRequest;
import com.fundlink.ai.gateway.LlmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 测试生成 Agent — 真正调 LLM 生成 previewData + 按 CONDITION 分支的测试用例
 */
@Slf4j
@Service
public class TestGenAgentImpl implements TestGenAgent {

    private final LlmGateway llmGateway;
    private final ObjectMapper json = new ObjectMapper();

    public TestGenAgentImpl(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public TestGenResult generate(FlowDsl flowDsl, List<FieldMappingSuggestion> fieldMappings,
                                   String providerCode) {
        String traceId = "testgen-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[TESTGEN] Start  traceId={}  provider={}  mappings={}",
                traceId, providerCode, fieldMappings != null ? fieldMappings.size() : 0);

        // Extract CONDITION branches
        List<FlowEdge> conditionBranches = extractConditionBranches(flowDsl);
        log.info("[TESTGEN] CONDITION branches found: {}  traceId={}", conditionBranches.size(), traceId);

        // Build prompt
        String prompt = buildPrompt(flowDsl, fieldMappings, providerCode, conditionBranches);
        log.info("[TESTGEN] Prompt built  traceId={}  len={}", traceId, prompt.length());

        // Call LLM
        LlmRequest request = LlmRequest.ofTask("testgen", prompt, traceId);
        LlmResponse response = llmGateway.chat(request);

        // Parse
        TestGenResult result = parseSafely(response.getContent(), conditionBranches);

        if (result.getParseError() != null) {
            log.error("[TESTGEN] Parse error  traceId={}  error={}", traceId, result.getParseError());
        } else {
            log.info("[TESTGEN] Done  traceId={}  testCases={}  previewDataKeys={}",
                    traceId,
                    result.getTestCases() != null ? result.getTestCases().size() : 0,
                    result.getPreviewData() != null ? result.getPreviewData().size() : 0);
        }
        return result;
    }

    /** Extract edges that originate from CONDITION nodes */
    private List<FlowEdge> extractConditionBranches(FlowDsl dsl) {
        if (dsl == null || dsl.getNodes() == null || dsl.getEdges() == null) {
            return Collections.emptyList();
        }
        Set<String> conditionNodeIds = new HashSet<>();
        for (FlowNode node : dsl.getNodes()) {
            if ("CONDITION".equals(node.getType())) {
                conditionNodeIds.add(node.getId());
            }
        }
        List<FlowEdge> branches = new ArrayList<>();
        for (FlowEdge edge : dsl.getEdges()) {
            if (conditionNodeIds.contains(edge.getSource())) {
                branches.add(edge);
            }
        }
        return branches;
    }

    private String buildPrompt(FlowDsl flowDsl, List<FieldMappingSuggestion> fieldMappings,
                               String providerCode, List<FlowEdge> branches) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
            你是资金接入系统测试专家。基于流程定义和字段映射，生成测试数据。

            ## 输出要求
            严格按以下 JSON Schema 输出，不要输出额外内容:

            ```json
            {
              "previewData": {
                "userInfo": {"realName": "张三", "idNo": "110101199001011234", "mobile": "13800138000"},
                "loanInfo": {"loanNo": "LN001", "amount": 50000},
                "riskData": {"score": 85, "level": "A"},
                "paymentData": {}
              },
              "testCases": [
                {
                  "name": "风控A级-正常放款",
                  "targetBranch": "ec1",
                  "scenarioType": "NORMAL",
                  "inputData": {"loanNo": "LN001", "amount": 50000},
                  "expectedContextKeys": {"riskData": "exists"},
                  "mockRules": [
                    {"sourceCode": "RISK", "responseJson": "{\\"score\\":85,\\"level\\":\\"A\\"}", "matchExpr": null, "delayMs": 0}
                  ],
                  "description": "风控返回A级，走正常放款分支"
                }
              ]
            }
            ```

            ## 规则
            - previewData: 为每个数据源(userInfo/loanInfo/riskData/paymentData)提供 2-3 条示例数据
            - testCases: CONDITION 节点的每条出边至少生成 1 个用例
            - targetBranch: 必须等于下面列出的 branch ID
            - inputData: FlowEngine 入口输入
            - expectedContextKeys: 期望在 FlowResult.data 中出现的 key，value 填 "exists" 表示仅验证 key 存在
            - mockRules: 每条 mock 对应一个 dataSourceCode，responseJson 为 mock 返回的 JSON 字符串

            """);

        // flowDsl summary
        try {
            sb.append("## 流程定义\n```json\n");
            sb.append(json.writeValueAsString(flowDsl));
            sb.append("\n```\n\n");
        } catch (Exception ignored) {}

        // field mappings
        sb.append("## 字段映射\n");
        if (fieldMappings != null) {
            for (FieldMappingSuggestion m : fieldMappings) {
                sb.append("- ").append(m.getFundField())
                        .append(" → ").append(m.getSourcePath());
                if (m.getTransform() != null) sb.append(" (").append(m.getTransform()).append(")");
                sb.append("\n");
            }
        }

        // CONDITION branches
        sb.append("\n## CONDITION 分支 (必须覆盖)\n");
        for (FlowEdge e : branches) {
            sb.append("- branchId=").append(e.getId())
                    .append("  source=").append(e.getSource())
                    .append("  target=").append(e.getTarget());
            if (e.getConditionExpr() != null) {
                sb.append("  conditionExpr=").append(e.getConditionExpr());
            }
            if (e.getLabel() != null) {
                sb.append("  label=").append(e.getLabel());
            }
            sb.append("\n");
        }

        sb.append("\n资金方: ").append(providerCode).append("\n");
        return sb.toString();
    }

    /** Parse LLM output using JsonNode — mirrors RequirementAgentImpl.parseSafely pattern */
    private TestGenResult parseSafely(String content, List<FlowEdge> branches) {
        TestGenResult result = new TestGenResult();
        result.setTestCases(new ArrayList<>());

        if (content == null || content.isBlank()) {
            result.setParseError("LLM returned empty response");
            return result;
        }

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

            // previewData
            JsonNode pd = root.path("previewData");
            if (!pd.isMissingNode() && pd.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> previewMap = json.convertValue(pd, Map.class);
                result.setPreviewData(previewMap);
            }

            // mockRules (global level)
            JsonNode mrNode = root.path("mockRules");
            if (mrNode.isArray()) {
                List<MockRuleSuggestion> mrs = new ArrayList<>();
                for (JsonNode n : mrNode) {
                    mrs.add(parseMockRule(n));
                }
                result.setMockRules(mrs);
            }

            // testCases
            JsonNode tcNode = root.path("testCases");
            if (tcNode.isArray()) {
                List<TestCase> cases = new ArrayList<>();
                Set<String> branchIds = new HashSet<>();
                for (FlowEdge e : branches) branchIds.add(e.getId());

                for (JsonNode c : tcNode) {
                    TestCase tc = new TestCase();
                    tc.setName(c.path("name").asText("未命名用例"));
                    tc.setTargetBranch(c.path("targetBranch").asText(null));
                    tc.setScenarioType(c.path("scenarioType").asText("NORMAL"));
                    tc.setDescription(c.path("description").asText(null));

                    // inputData
                    JsonNode inData = c.path("inputData");
                    if (!inData.isMissingNode() && inData.isObject()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> inMap = json.convertValue(inData, Map.class);
                        tc.setInputData(inMap);
                    }

                    // expectedContextKeys
                    JsonNode eck = c.path("expectedContextKeys");
                    if (!eck.isMissingNode() && eck.isObject()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> eckMap = json.convertValue(eck, Map.class);
                        tc.setExpectedContextKeys(eckMap);
                    }

                    // per-case mockRules
                    JsonNode cmr = c.path("mockRules");
                    if (cmr.isArray()) {
                        List<MockRuleSuggestion> cmrs = new ArrayList<>();
                        for (JsonNode n : cmr) {
                            cmrs.add(parseMockRule(n));
                        }
                        tc.setMockRules(cmrs);
                    }

                    // Validate targetBranch
                    if (tc.getTargetBranch() == null) {
                        log.warn("[TESTGEN] TestCase missing targetBranch: {}", tc.getName());
                    } else if (!branchIds.isEmpty() && !branchIds.contains(tc.getTargetBranch())) {
                        log.warn("[TESTGEN] TestCase targetBranch '{}' not in known branches: {}",
                                tc.getTargetBranch(), branchIds);
                    }

                    cases.add(tc);
                }
                result.setTestCases(cases);
            }
        } catch (Exception e) {
            log.error("[TESTGEN] Parse failed: {}", e.getMessage());
            log.debug("[TESTGEN] Raw content:\n{}", content);
            result.setParseError("LLM response parse failed: " + e.getMessage());
        }

        return result;
    }

    private MockRuleSuggestion parseMockRule(JsonNode n) {
        MockRuleSuggestion r = new MockRuleSuggestion();
        r.setRuleName(n.path("ruleName").asText(null));
        r.setSourceCode(n.path("sourceCode").asText(null));
        r.setMatchExpr(n.path("matchExpr").asText(null));
        r.setResponseJson(n.path("responseJson").asText(null));
        r.setDelayMs(n.path("delayMs").asInt(0));
        return r;
    }
}
