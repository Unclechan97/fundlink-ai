package com.fundlink.ai.tools;

import com.fundlink.ai.gateway.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 回归：listener 为 run() 局部参数，共享实例并发调用时 trace 不串台。
 */
class ToolCallingLoopTest {

    /** 每个 traceId 的第一个请求返回 tool call，之后返回最终回答（并发下按 trace 隔离） */
    private static class FakeLlmGateway implements LlmGateway {
        private final ConcurrentHashMap<String, Integer> perTraceCalls = new ConcurrentHashMap<>();

        @Override
        public LlmResponse chat(LlmRequest request) {
            int n = perTraceCalls.merge(request.getTraceId(), 1, Integer::sum);
            if (n == 1) {
                return LlmResponse.ofToolCalls(List.of(
                        new ToolCall("call-1", "echo", Map.of("text", "hello"))),
                        "fake", "fake-model", TokenUsage.of(1, 1), 1);
            }
            return LlmResponse.of("最终诊断", "fake", "fake-model", TokenUsage.of(1, 1), 1);
        }
    }

    private static class RecordingListener extends ToolLoopListener.Adapter {
        final String owner;
        final List<String> toolCalls = new CopyOnWriteArrayList<>();

        RecordingListener(String owner) { this.owner = owner; }

        @Override
        public void onToolCall(int round, String toolName, String args, String result) {
            toolCalls.add(owner + ":" + toolName);
        }
    }

    @Test
    @DisplayName("B2: listener 是局部参数 — 并发两个 run 各自回调各自的 listener")
    void shouldNotCrossTalkBetweenConcurrentRuns() throws Exception {
        FakeLlmGateway llm = new FakeLlmGateway();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public ToolDefinition getDefinition() {
                return new ToolDefinition("echo", "回显工具", Map.of("type", "object", "properties", Map.of()));
            }
            @Override public String execute(ToolCall call) {
                return "{\"echoed\": \"" + call.arg("text") + "\"}";
            }
        });

        // 共享同一个 ToolCallingLoop 实例（模拟 Controller 单例）
        ToolCallingLoop loop = new ToolCallingLoop(llm, registry, 3);

        RecordingListener l1 = new RecordingListener("run1");
        RecordingListener l2 = new RecordingListener("run2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        Future<String> f1 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return loop.run("sys1", "user1", "trace-1", l1);
        });
        Future<String> f2 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return loop.run("sys2", "user2", "trace-2", l2);
        });

        ready.await();
        start.countDown();
        String r1 = f1.get(10, TimeUnit.SECONDS);
        String r2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(r1).isEqualTo("最终诊断");
        assertThat(r2).isEqualTo("最终诊断");
        // 每个 listener 只收到自己那次运行的 tool 回调
        assertThat(l1.toolCalls).containsExactly("run1:echo");
        assertThat(l2.toolCalls).containsExactly("run2:echo");
    }

    @Test
    @DisplayName("B2: run(3 参数) 不传 listener 也不 NPE")
    void shouldRunWithoutListener() {
        FakeLlmGateway llm = new FakeLlmGateway();
        ToolCallingLoop loop = new ToolCallingLoop(llm, new ToolRegistry(), 3);

        String result = loop.run("sys", "user", "trace-x");

        assertThat(result).isEqualTo("最终诊断");
    }
}
