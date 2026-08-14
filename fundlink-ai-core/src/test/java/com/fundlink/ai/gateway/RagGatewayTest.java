package com.fundlink.ai.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RagGateway 契约回归（B3）：
 * <ul>
 *   <li>配置 internal-key 时请求携带 X-Internal-Token</li>
 *   <li>未配置 key 时不带头（向后兼容）</li>
 *   <li>degraded=true → available=false（调用方不得拿降级上下文继续诊断）</li>
 *   <li>HTTP 错误 / 连接失败 → available=false，不抛异常</li>
 * </ul>
 */
class RagGatewayTest {

    private static final String OK_JSON =
            "{\"results\":[{\"text\":\"这是一个足够长的历史案例文本用于测试检索结果返回\"}]}";

    private HttpServer server;
    private int port;
    private final AtomicReference<String> searchResponse = new AtomicReference<>(OK_JSON);
    private final AtomicInteger searchStatus = new AtomicInteger(200);
    private final AtomicReference<String> capturedToken = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        searchResponse.set(OK_JSON);
        searchStatus.set(200);
        capturedToken.set(null);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            capturedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            byte[] resp = "/search".equals(exchange.getRequestURI().getPath())
                    ? searchResponse.get().getBytes(StandardCharsets.UTF_8)
                    : new byte[0];
            int status = "/search".equals(exchange.getRequestURI().getPath())
                    ? searchStatus.get() : 404;
            exchange.sendResponseHeaders(status, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("B3: 配置 internal-key 时 /search 请求携带 X-Internal-Token")
    void shouldSendInternalTokenWhenConfigured() {
        RagGateway gw = new RagGateway("http://127.0.0.1:" + port, "secret-key-123");

        RagGateway.SearchResult r = gw.search("测试查询", 3);

        assertThat(r.isAvailable()).isTrue();
        assertThat(r.getResults()).hasSize(1);
        assertThat(capturedToken.get()).isEqualTo("secret-key-123");
    }

    @Test
    @DisplayName("B3: 未配置 internal-key 时不带 X-Internal-Token（向后兼容）")
    void shouldNotSendTokenWhenMissing() {
        RagGateway gw = new RagGateway("http://127.0.0.1:" + port, "");

        RagGateway.SearchResult r = gw.search("测试查询", 3);

        assertThat(r.isAvailable()).isTrue();
        assertThat(capturedToken.get()).isNull();
    }

    @Test
    @DisplayName("B3: degraded=true → available=false（不得拿降级上下文继续诊断）")
    void shouldMarkDegradedAsUnavailable() {
        searchResponse.set("{\"results\":[{\"text\":\"这是一个足够长的历史案例文本用于测试检索结果返回\"}],\"degraded\":true}");
        RagGateway gw = new RagGateway("http://127.0.0.1:" + port, "k");

        RagGateway.SearchResult r = gw.search("测试查询", 3);

        assertThat(r.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("B3: HTTP 500 → available=false，不抛异常")
    void shouldMarkHttpErrorAsUnavailable() {
        searchStatus.set(500);
        RagGateway gw = new RagGateway("http://127.0.0.1:" + port, "k");

        RagGateway.SearchResult r = gw.search("测试查询", 3);

        assertThat(r.isAvailable()).isFalse();
        assertThat(r.getResults()).isEmpty();
    }

    @Test
    @DisplayName("B3: 连接失败 → available=false，不抛异常")
    void shouldNotThrowOnConnectionFailure() {
        RagGateway gw = new RagGateway("http://127.0.0.1:1", "k");

        RagGateway.SearchResult r = gw.search("测试查询", 3);

        assertThat(r.isAvailable()).isFalse();
    }
}
