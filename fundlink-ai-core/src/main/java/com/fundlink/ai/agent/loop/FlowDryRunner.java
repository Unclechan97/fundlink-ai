package com.fundlink.ai.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.requirement.FlowEdge;
import com.fundlink.ai.agent.requirement.FlowNode;
import com.fundlink.ai.agent.testgen.MockRuleSuggestion;
import com.fundlink.ai.agent.testgen.TestCase;
import com.fundlink.ai.agent.testgen.TestGenResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 流程干跑测试器 — Mock 注入 + executeSync + 分支覆盖检查 (设计 §3.3)
 * <p>
 * 对每条 CONDITION 出边: 创建临时 mock → 翻转数据源 useMock → 调 dry-run → 验证 → 禁用 mock + 恢复 useMock
 */
@Slf4j
@Service
public class FlowDryRunner {

    private final String fundlinkUrl;
    private final ObjectMapper json = new ObjectMapper();

    public FlowDryRunner(@Value("${fundlink.admin.base-url:http://localhost:8080}") String fundlinkUrl) {
        this.fundlinkUrl = fundlinkUrl;
    }

    /**
     * 干跑所有 CONDITION 分支
     * @param taskId   AI 任务 ID (用于 mock 命名)
     * @param flowId   FundLink flow_definition.id
     * @param testGen  TestGen 输出 (含按分支的 testCases)
     * @param nodes    流程节点
     * @param edges    流程边
     */
    public DryRunResult dryRun(Long taskId, Long flowId, TestGenResult testGen,
                               List<FlowNode> nodes, List<FlowEdge> edges) {
        List<BranchResult> results = new ArrayList<>();

        // 1. 清理旧 mock + 准备 CONDITION 分支
        Set<String> conditionNodeIds = new HashSet<>();
        for (FlowNode n : nodes) {
            if ("CONDITION".equals(n.getType())) conditionNodeIds.add(n.getId());
        }

        List<FlowEdge> condBranches = new ArrayList<>();
        for (FlowEdge e : edges) {
            if (conditionNodeIds.contains(e.getSource())) condBranches.add(e);
        }

        if (condBranches.isEmpty()) {
            log.info("[DRY-RUN] No CONDITION branches — skipping");
            return DryRunResult.ok(results);
        }

        try {
            // 2. 清理旧 TEST mock
            cleanupTestMocks(taskId);

            // 3. 保存数据源 useMock 状态
            Map<String, Boolean> originalUseMock = new HashMap<>();

            // 4. 逐分支测试
            for (FlowEdge branch : condBranches) {
                TestCase tc = findTestCase(testGen, branch.getId());
                if (tc == null) {
                    results.add(BranchResult.fail(branch.getId(), "N/A",
                            "No test case for branch " + branch.getId()));
                    continue;
                }

                BranchResult br = runBranch(taskId, flowId, branch, tc, originalUseMock);
                results.add(br);
            }

            // 5. 恢复数据源 useMock
            restoreUseMock(originalUseMock);

        } catch (Exception e) {
            log.error("[DRY-RUN] Fatal error: {}", e.getMessage(), e);
            return DryRunResult.fail(results, e.getMessage());
        }

        boolean allPassed = results.stream().allMatch(BranchResult::isSuccess);
        return allPassed ? DryRunResult.ok(results) : DryRunResult.fail(results,
                "Some branches failed: " + results.stream().filter(r -> !r.isSuccess()).count());
    }

    private BranchResult runBranch(Long taskId, Long flowId, FlowEdge branch, TestCase tc,
                                    Map<String, Boolean> originalUseMock) {
        String branchId = branch.getId();
        List<Long> createdMockIds = new ArrayList<>();

        try {
            // Create mock rules
            if (tc.getMockRules() != null) {
                for (MockRuleSuggestion mr : tc.getMockRules()) {
                    String ruleName = "TEST_" + taskId + "_" + mr.getSourceCode() + "_" + branchId;
                    Long mockId = createMockRule(ruleName, mr.getSourceCode(),
                            mr.getResponseJson(), mr.getMatchExpr(), mr.getDelayMs());
                    if (mockId != null) createdMockIds.add(mockId);

                    // Flip data source useMock
                    if (!originalUseMock.containsKey(mr.getSourceCode())) {
                        Boolean orig = getUseMock(mr.getSourceCode());
                        originalUseMock.put(mr.getSourceCode(), orig);
                        setUseMock(mr.getSourceCode(), true);
                    }
                }
            }

            // Execute dry-run
            Map<String, Object> result = executeDryRun(flowId, tc.getInputData());
            if (result == null) {
                return BranchResult.fail(branchId, tc.getMockRules() != null
                        ? tc.getMockRules().stream().map(MockRuleSuggestion::getSourceCode)
                        .findFirst().orElse("N/A") : "N/A",
                        "Dry-run execution returned null");
            }

            Boolean success = (Boolean) result.getOrDefault("success", false);
            if (!Boolean.TRUE.equals(success)) {
                return BranchResult.fail(branchId, "N/A",
                        "Execution failed: " + result.getOrDefault("errorMsg", "unknown"));
            }

            // Verify expected context keys
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.getOrDefault("data", Map.of());
            if (tc.getExpectedContextKeys() != null) {
                for (String key : tc.getExpectedContextKeys().keySet()) {
                    if (!data.containsKey(key)) {
                        return BranchResult.fail(branchId, "N/A",
                                "Missing expected context key: " + key);
                    }
                }
            }

            return BranchResult.ok(branchId, tc.getMockRules() != null
                    ? tc.getMockRules().stream().map(MockRuleSuggestion::getSourceCode)
                    .findFirst().orElse("N/A") : "N/A", data);

        } catch (Exception e) {
            log.error("[DRY-RUN] Branch {} error: {}", branchId, e.getMessage());
            return BranchResult.fail(branchId, "N/A", e.getMessage());
        } finally {
            // Disable created mocks (留痕)
            for (Long mockId : createdMockIds) {
                disableMock(mockId);
            }
        }
    }

    private TestCase findTestCase(TestGenResult testGen, String branchId) {
        if (testGen.getTestCases() == null) return null;
        return testGen.getTestCases().stream()
                .filter(tc -> branchId.equals(tc.getTargetBranch()))
                .findFirst().orElse(null);
    }

    // -- FundLink HTTP helpers (mirrors ConfigWriter pattern) --

    private void cleanupTestMocks(Long taskId) throws Exception {
        String prefix = "TEST_" + taskId + "_";
        // List all mocks → disable those matching prefix
        String resp = getJson("/api/admin/mock-rules?page=1&size=200");
        if (resp == null) return;
        Map<String, Object> root = json.readValue(resp, Map.class);
        Object data = root.get("data");
        List<Map> records = extractRecords(data);
        for (Map r : records) {
            Object name = r.get("ruleName");
            if (name != null && name.toString().startsWith(prefix)) {
                Object id = r.get("id");
                if (id != null) {
                    putJson("/api/admin/mock-rules/" + id,
                            "{\"enabled\":0}");
                }
            }
        }
    }

    private Long createMockRule(String ruleName, String sourceCode, String responseJson,
                                 String matchExpr, int delayMs) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleName", ruleName);
        body.put("sourceCode", sourceCode);
        body.put("responseJson", responseJson);
        body.put("responseCode", 200);
        body.put("matchExpr", matchExpr != null ? matchExpr : "");
        body.put("delayMs", delayMs);
        body.put("enabled", 1);

        String resp = postJson("/api/admin/mock-rules", json.writeValueAsString(body));
        if (resp != null) {
            Map<String, Object> root = json.readValue(resp, Map.class);
            Object d = root.get("data");
            if (d instanceof Number) return ((Number) d).longValue();
            if (d instanceof Map && ((Map) d).get("id") instanceof Number) {
                return ((Number) ((Map) d).get("id")).longValue();
            }
        }
        return null;
    }

    private Boolean getUseMock(String sourceCode) throws Exception {
        String resp = getJson("/api/admin/data-sources?page=1&size=200");
        if (resp == null) return false;
        Map<String, Object> root = json.readValue(resp, Map.class);
        List<Map> records = extractRecords(root.get("data"));
        for (Map r : records) {
            if (sourceCode.equals(r.get("sourceCode"))) {
                Object useMock = r.get("useMock");
                return useMock instanceof Number && ((Number) useMock).intValue() == 1;
            }
        }
        return false;
    }

    private void setUseMock(String sourceCode, boolean enabled) throws Exception {
        // Find data source id first
        String resp = getJson("/api/admin/data-sources?page=1&size=200");
        if (resp == null) return;
        Map<String, Object> root = json.readValue(resp, Map.class);
        List<Map> records = extractRecords(root.get("data"));
        for (Map r : records) {
            if (sourceCode.equals(r.get("sourceCode"))) {
                Object id = r.get("id");
                if (id != null) {
                    putJson("/api/admin/data-sources/" + id,
                            "{\"useMock\":" + (enabled ? 1 : 0) + "}");
                }
                break;
            }
        }
    }

    private void restoreUseMock(Map<String, Boolean> original) {
        for (Map.Entry<String, Boolean> e : original.entrySet()) {
            try {
                setUseMock(e.getKey(), e.getValue());
            } catch (Exception ex) {
                log.warn("[DRY-RUN] Failed to restore useMock for {}: {}", e.getKey(), ex.getMessage());
            }
        }
    }

    private Map<String, Object> executeDryRun(Long flowId, Map<String, Object> inputData) throws Exception {
        Map<String, Object> body = Map.of("inputData", inputData != null ? inputData : Map.of());
        String bodyJson = json.writeValueAsString(body);
        log.info("[DRYRUN] >>> EXECUTE  flowId={}  input={}", flowId,
                bodyJson.replaceAll("\\s+", " "));

        String resp = postJson("/api/upstream/flows/" + flowId + "/dry-run", bodyJson);
        if (resp == null) {
            log.warn("[DRYRUN] <<< EXECUTE NULL");
            return null;
        }
        log.info("[DRYRUN] <<< EXECUTE  resp={}", resp.replaceAll("\\s+", " "));

        Map<String, Object> root = json.readValue(resp, Map.class);
        Object data = root.get("data");
        if (data instanceof Map) return (Map<String, Object>) data;
        return Map.of();
    }

    private void disableMock(Long mockId) {
        try {
            putJson("/api/admin/mock-rules/" + mockId, "{\"enabled\":0}");
        } catch (Exception e) {
            log.warn("[DRY-RUN] Failed to disable mock {}: {}", mockId, e.getMessage());
        }
    }

    // -- raw HTTP --

    @SuppressWarnings("unchecked")
    private List<Map> extractRecords(Object data) {
        if (data instanceof Map) {
            Object records = ((Map) data).get("records");
            if (records instanceof List) return (List<Map>) records;
        }
        if (data instanceof List) return (List<Map>) data;
        return Collections.emptyList();
    }

    private String getJson(String path) throws Exception {
        return http("GET", path, null);
    }

    private String postJson(String path, String body) throws Exception {
        return http("POST", path, body);
    }

    private String putJson(String path, String body) throws Exception {
        return http("PUT", path, body);
    }

    private String http(String method, String path, String body) throws Exception {
        URI uri = new URI(fundlinkUrl + path);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        if (body != null && ("POST".equals(method) || "PUT".equals(method))) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        if (conn.getResponseCode() >= 400) {
            String err = conn.getErrorStream() != null
                    ? new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                    : "HTTP " + conn.getResponseCode();
            log.warn("[DRY-RUN] {} {} → HTTP {} err={}", method, path, conn.getResponseCode(), err);
            return null;
        }

        return new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    // -- result classes --

    public static class DryRunResult {
        private boolean success;
        private List<BranchResult> branches;
        private String errorMsg;

        public static DryRunResult ok(List<BranchResult> branches) {
            DryRunResult r = new DryRunResult();
            r.success = true;
            r.branches = branches;
            return r;
        }

        public static DryRunResult fail(List<BranchResult> branches, String errorMsg) {
            DryRunResult r = new DryRunResult();
            r.success = false;
            r.branches = branches;
            r.errorMsg = errorMsg;
            return r;
        }

        public boolean isSuccess() { return success; }
        public List<BranchResult> getBranches() { return branches; }
        public String getErrorMsg() { return errorMsg; }
    }

    public static class BranchResult {
        private boolean success;
        private String branchId;
        private String sourceCode;
        private String error;
        private Map<String, Object> contextData;

        public static BranchResult ok(String branchId, String sourceCode, Map<String, Object> data) {
            BranchResult r = new BranchResult();
            r.success = true;
            r.branchId = branchId;
            r.sourceCode = sourceCode;
            r.contextData = data;
            return r;
        }

        public static BranchResult fail(String branchId, String sourceCode, String error) {
            BranchResult r = new BranchResult();
            r.success = false;
            r.branchId = branchId;
            r.sourceCode = sourceCode;
            r.error = error;
            return r;
        }

        public boolean isSuccess() { return success; }
        public String getBranchId() { return branchId; }
        public String getSourceCode() { return sourceCode; }
        public String getError() { return error; }
        public Map<String, Object> getContextData() { return contextData; }
    }
}
