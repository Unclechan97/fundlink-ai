package com.fundlink.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.requirement.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConfigWriter 幂等回归（B4 六条）：
 * 1. listAll 翻页取全量（>200 条不再必然重复创建）
 * 2. getOrCreateFlow 存在时 PUT 更新 graphData（EDIT_AND_RETRY 生效）
 * 3. find-then-create TOCTOU：创建冲突降级为重新查询复用
 * 4. mappings 逐条 upsert（不再先删后建、不再 DELETE）
 * 5. 创建失败（HTTP/业务码）计入 WriteResult 错误
 * 6. 错误响应体为空时不 NPE
 */
class ConfigWriterTest {

    /** 记录所有打到 mock 的请求，供断言 */
    private static class Request {
        final String method;
        final String path;
        final String query;
        final String body;
        Request(String m, String p, String q, String b) { method = m; path = p; query = q; body = b; }
    }

    interface RouteHandler {
        int status();
        String body(String query);
    }

    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, RouteHandler> routes = new ConcurrentHashMap<>();
    private HttpServer server;
    private ConfigWriter writer;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(method, path, query == null ? "" : query, body));

            RouteHandler h = routes.get(method + " " + path);
            if (h == null) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] resp = h.body(query == null ? "" : query).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(h.status(), resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
        writer = new ConfigWriter("http://127.0.0.1:" + port);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ── helpers ──

    private long countRequests(String method, String path) {
        return requests.stream().filter(r -> r.method.equals(method) && r.path.equals(path)).count();
    }

    private Request lastRequest(String method, String path) {
        return requests.stream()
                .filter(r -> r.method.equals(method) && r.path.equals(path))
                .reduce((a, b) -> b).orElse(null);
    }

    private RouteHandler json(int status, String json) {
        return new RouteHandler() {
            @Override public int status() { return status; }
            @Override public String body(String q) { return json; }
        };
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String records(List<Map<String, Object>> items) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "code", 0,
                    "data", Map.of("records", items, "total", items.size())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> providerPage(long fromId, int count) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(Map.of("id", fromId + i, "providerCode", "PG" + (fromId + i)));
        }
        return list;
    }

    /** 常规成功路径的 FundLink mock：全部不存在 → POST 建，GET 空 */
    private void installDefaultSuccessRoutes() {
        routes.put("GET /api/admin/providers", json(200, records(List.of())));
        routes.put("POST /api/admin/providers", json(200, "{\"code\":0,\"data\":101}"));
        routes.put("GET /api/admin/templates", json(200, records(List.of())));
        routes.put("POST /api/admin/templates", json(200, "{\"code\":0,\"data\":102}"));
        routes.put("GET /api/admin/flows", json(200, records(List.of())));
        routes.put("POST /api/admin/flows", json(200, "{\"code\":0,\"data\":103}"));
    }

    private RequirementResult buildResult(List<FieldMappingSuggestion> mappings, FlowDsl dsl) {
        RequirementResult r = new RequirementResult();
        ProviderConfig cfg = new ProviderConfig();
        cfg.setProviderName("测试银行");
        cfg.setBaseUrl("http://bank/api");
        r.setProviderConfig(cfg);
        r.setFieldMappings(mappings != null ? mappings : new ArrayList<>());
        r.setFlowDsl(dsl);
        return r;
    }

    private FieldMappingSuggestion mapping(String fundField, String sourcePath) {
        FieldMappingSuggestion m = new FieldMappingSuggestion();
        m.setFundField(fundField);
        m.setSourcePath(sourcePath);
        m.setTransform(null);
        return m;
    }

    // ── B4-1: 翻页取全量 ──

    @Test
    @DisplayName("B4-1: provider 在第 3 页（>200 条）也能查到，不重复创建")
    void shouldFindProviderBeyondFirstPage() {
        // 450 个 provider，目标 PG450 在最后一页；total 恒定 450（真实 FundLink 语义）
        routes.put("GET /api/admin/providers", new RouteHandler() {
            @Override public int status() { return 200; }
            @Override public String body(String q) {
                int p = Integer.parseInt(q.replaceAll(".*page=(\\d+).*", "$1"));
                long from = (p - 1) * 200L + 1;
                try {
                    return MAPPER.writeValueAsString(Map.of(
                            "code", 0,
                            "data", Map.of("records", providerPage(from, 200), "total", 450)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        routes.put("GET /api/admin/templates", json(200, records(List.of())));
        routes.put("POST /api/admin/templates", json(200, "{\"code\":0,\"data\":102}"));
        routes.put("GET /api/admin/flows", json(200, records(List.of())));
        routes.put("POST /api/admin/flows", json(200, "{\"code\":0,\"data\":103}"));

        ConfigWriter.WriteResult r = writer.writeAll(buildResult(null, null), "PG450", "LOAN");

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getProviderId()).isEqualTo(450);
        // 未重复创建 provider
        assertThat(countRequests("POST", "/api/admin/providers")).isZero();
        // 翻页 3 次（450 = 200+200+50）
        assertThat(countRequests("GET", "/api/admin/providers")).isEqualTo(3);
    }

    // ── B4-2: flow 存在时 PUT 更新 graphData ──

    @Test
    @DisplayName("B4-2: EDIT_AND_RETRY 修正后的 graphData 覆盖旧 flow")
    void shouldUpdateExistingFlowGraphData() {
        installDefaultSuccessRoutes();
        routes.put("GET /api/admin/flows", json(200, records(List.of(
                Map.of("id", 300, "flowCode", "LOAN_TESTBANK")
        ))));
        routes.put("PUT /api/admin/flows/300", json(200, "{\"code\":0}"));
        routes.put("GET /api/admin/templates/102/mappings", json(200, "{\"code\":0,\"data\":[]}"));
        routes.put("POST /api/admin/templates/102/mappings", json(200, "{\"code\":0,\"data\":1}"));

        FlowDsl dsl = new FlowDsl();
        FlowNode node = new FlowNode();
        node.setId("n1");
        node.setType("START");
        dsl.setNodes(List.of(node));
        dsl.setEdges(List.of());

        ConfigWriter.WriteResult r = writer.writeAll(
                buildResult(List.of(mapping("loanNo", "loanInfo.loanNo")), dsl),
                "TESTBANK", "LOAN");

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getFlowId()).isEqualTo(300);
        // 存在 → PUT 更新，不再 POST 新建
        assertThat(countRequests("PUT", "/api/admin/flows/300")).isEqualTo(1);
        assertThat(countRequests("POST", "/api/admin/flows")).isZero();
        Request put = lastRequest("PUT", "/api/admin/flows/300");
        // graphData 是 body 内的 JSON 字符串 — 解析后断言修正后的节点在
        try {
            String graphData = MAPPER.readTree(put.body).get("graphData").asText();
            assertThat(graphData).contains("\"n1\"");
        } catch (Exception e) {
            throw new AssertionError("PUT body graphData 解析失败: " + put.body, e);
        }
    }

    // ── B4-3: TOCTOU 降级复用 ──

    @Test
    @DisplayName("B4-3: 创建冲突（业务码!=0）→ 重新查询复用")
    void shouldReuseProviderWhenCreateFailsWithBusinessError() {
        AtomicInteger getCalls = new AtomicInteger(0);
        routes.put("GET /api/admin/providers", new RouteHandler() {
            @Override public int status() { return 200; }
            @Override public String body(String q) {
                // 第一次 GET（查重）为空；创建失败后的第二次 GET 返回并发创建的记录
                return getCalls.incrementAndGet() == 1
                        ? records(List.of())
                        : records(List.of(Map.of("id", 55, "providerCode", "TESTBANK")));
            }
        });
        routes.put("POST /api/admin/providers", json(200, "{\"code\":-1,\"msg\":\"providerCode 已存在\"}"));
        routes.put("GET /api/admin/templates", json(200, records(List.of())));
        routes.put("POST /api/admin/templates", json(200, "{\"code\":0,\"data\":102}"));
        routes.put("GET /api/admin/flows", json(200, records(List.of())));
        routes.put("POST /api/admin/flows", json(200, "{\"code\":0,\"data\":103}"));

        ConfigWriter.WriteResult r = writer.writeAll(buildResult(null, null), "TESTBANK", "LOAN");

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getProviderId()).isEqualTo(55);
        assertThat(countRequests("POST", "/api/admin/providers")).isEqualTo(1);
    }

    // ── B4-4: mappings 逐条 upsert ──

    @Test
    @DisplayName("B4-4: 已存在 mapping PUT 更新，新 mapping POST 新建，零 DELETE")
    void shouldUpsertMappings() {
        installDefaultSuccessRoutes();
        routes.put("GET /api/admin/templates", json(200, records(List.of(
                Map.of("id", 102, "templateCode", "AI_TESTBANK")
        ))));
        routes.put("PUT /api/admin/templates/102", json(200, "{\"code\":0}"));
        routes.put("GET /api/admin/flows", json(200, records(List.of())));
        routes.put("POST /api/admin/flows", json(200, "{\"code\":0,\"data\":103}"));
        // 现有 mappings：loanNo 已存在(id=1)，stale 为历史残留
        routes.put("GET /api/admin/templates/102/mappings", json(200,
                "{\"code\":0,\"data\":[{\"id\":1,\"fundField\":\"loanNo\"},{\"id\":2,\"fundField\":\"stale\"}]}"));
        routes.put("PUT /api/admin/templates/102/mappings/1", json(200, "{\"code\":0}"));
        routes.put("POST /api/admin/templates/102/mappings", json(200, "{\"code\":0,\"data\":5}"));

        ConfigWriter.WriteResult r = writer.writeAll(
                buildResult(List.of(
                        mapping("loanNo", "loanInfo.loanNo.V2"),  // 已存在 → PUT
                        mapping("amount", "loanInfo.amount")      // 新 → POST
                ), null),
                "TESTBANK", "LOAN");

        assertThat(r.isSuccess()).isTrue();
        assertThat(r.getMappingCount()).isEqualTo(2);
        assertThat(countRequests("PUT", "/api/admin/templates/102/mappings/1")).isEqualTo(1);
        assertThat(countRequests("POST", "/api/admin/templates/102/mappings")).isEqualTo(1);
        // 不再先删后建
        assertThat(requests.stream().filter(req -> req.method.equals("DELETE")).count()).isZero();
    }

    // ── B4-5/6: 失败可感知 + 错误路径不 NPE ──

    @Test
    @DisplayName("B4-5/6: HTTP 500 且无错误响应体 → WriteResult 失败且不 NPE")
    void shouldFailGracefullyOnHttp500WithoutBody() {
        routes.put("GET /api/admin/providers", json(200, records(List.of())));
        routes.put("POST /api/admin/providers", new RouteHandler() {
            @Override public int status() { return 500; }
            @Override public String body(String q) { return ""; }
        });

        ConfigWriter.WriteResult r = writer.writeAll(buildResult(null, null), "TESTBANK", "LOAN");

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getError()).contains("500");
    }

    @Test
    @DisplayName("B4-5: HTTP 200 业务码 !=0 → 计入错误（无并发复用时）")
    void shouldFailOnBusinessErrorWithoutConcurrentRecord() {
        routes.put("GET /api/admin/providers", json(200, records(List.of())));
        routes.put("POST /api/admin/providers", json(200, "{\"code\":1,\"msg\":\"创建失败\"}"));

        ConfigWriter.WriteResult r = writer.writeAll(buildResult(null, null), "TESTBANK", "LOAN");

        assertThat(r.isSuccess()).isFalse();
        assertThat(r.getError()).contains("business error");
    }
}
