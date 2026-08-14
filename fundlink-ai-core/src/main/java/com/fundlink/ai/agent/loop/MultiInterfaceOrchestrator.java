package com.fundlink.ai.agent.loop;

import com.fundlink.ai.agent.ConfigWriter;
import com.fundlink.ai.agent.PromptBuilder;
import com.fundlink.ai.agent.requirement.MultiInterfaceResult;
import com.fundlink.ai.agent.requirement.RequirementAgent;
import com.fundlink.ai.agent.requirement.RequirementResult;
import com.fundlink.ai.agent.split.InterfaceSegment;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多接口并行处理器（Phase 3）。
 *
 * 不修改现有单接口 AgentLoopOrchestrator，纯增量。
 *
 * 流程：
 * 1. 接收拆分好的 InterfaceSegment 列表
 * 2. 并行调用 RequirementAgent.analyze()（CompletableFuture + 有界线程池）
 * 3. 每个接口独立 Prompt、独立 LLM 调用、错误隔离
 * 4. 聚合为 MultiInterfaceResult
 * <p>
 * SSE 已移除（2026-08）：事件发布改日志。
 */
@Slf4j
public class MultiInterfaceOrchestrator {

    private final RequirementAgent requirementAgent;
    private final ConfigWriter configWriter;
    private final PromptBuilder promptBuilder;
    private final Executor executor;
    private final boolean ownsExecutor;

    public MultiInterfaceOrchestrator(RequirementAgent requirementAgent,
                                       ConfigWriter configWriter,
                                       PromptBuilder promptBuilder) {
        this(requirementAgent, configWriter, promptBuilder, defaultExecutor(), true);
    }

    /** 测试用构造函数 — 注入自定义 Executor（生命周期由调用方负责） */
    public MultiInterfaceOrchestrator(RequirementAgent requirementAgent,
                                ConfigWriter configWriter,
                                PromptBuilder promptBuilder,
                                Executor executor) {
        this(requirementAgent, configWriter, promptBuilder, executor, false);
    }

    private MultiInterfaceOrchestrator(RequirementAgent requirementAgent,
                                       ConfigWriter configWriter,
                                       PromptBuilder promptBuilder,
                                       Executor executor,
                                       boolean ownsExecutor) {
        this.requirementAgent = requirementAgent;
        this.configWriter = configWriter;
        this.promptBuilder = promptBuilder;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
    }

    /** 有界池替代 newCachedThreadPool — 并发可控，daemon 线程不阻塞 JVM 退出 */
    private static Executor defaultExecutor() {
        AtomicInteger seq = new AtomicInteger(0);
        return Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "multi-interface-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /** 释放自有线程池（测试/独立运行时使用） */
    public void shutdown() {
        if (ownsExecutor && executor instanceof ExecutorService es) {
            es.shutdownNow();
        }
    }

    /**
     * 并行处理多个接口。
     *
     * @param segments     拆分后的接口片段
     * @param providerCode 资金方编码
     * @param flowType     流程类型
     * @return 聚合结果
     */
    public MultiInterfaceResult processInterfaces(List<InterfaceSegment> segments,
                                                   String providerCode, String flowType) {
        MultiInterfaceResult aggregate = new MultiInterfaceResult();
        aggregate.setProviderCode(providerCode);
        aggregate.setTotalCount(segments.size());

        if (segments.isEmpty()) {
            return aggregate;
        }

        // 退化：单接口 → 不启动并行（保持与现有逻辑一致）
        if (segments.size() == 1) {
            InterfaceSegment seg = segments.get(0);
            log.info("[MULTI] interface:start  interfaceId={}  name={}  index={}/{}",
                    seg.getInterfaceId(), seg.getInterfaceName(), seg.getIndex(), 1);
            RequirementResult result = processOneInterface(seg, providerCode, flowType,
                    segments, 1, 1);
            MultiInterfaceResult.InterfaceResultItem item =
                    buildItem(seg, result);
            log.info("[MULTI] interface:complete  interfaceId={}  name={}  status={}",
                    seg.getInterfaceId(), seg.getInterfaceName(), item.getStatus());
            aggregate.getInterfaces().add(item);
            aggregate.setSuccessCount(result.getParseError() == null ? 1 : 0);
            aggregate.setFailedCount(result.getParseError() != null ? 1 : 0);
            return aggregate;
        }

        int total = segments.size();

        // 并行处理
        List<CompletableFuture<MultiInterfaceResult.InterfaceResultItem>> futures =
                new ArrayList<>();
        for (InterfaceSegment seg : segments) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                log.info("[MULTI] interface:start  interfaceId={}  name={}  index={}/{}",
                        seg.getInterfaceId(), seg.getInterfaceName(), seg.getIndex(), total);

                RequirementResult result = processOneInterface(
                        seg, providerCode, flowType, segments, seg.getIndex() + 1, total);

                MultiInterfaceResult.InterfaceResultItem item = buildItem(seg, result);

                log.info("[MULTI] interface:complete  interfaceId={}  name={}  status={}",
                        seg.getInterfaceId(), seg.getInterfaceName(), item.getStatus());

                return item;
            }, executor).exceptionally(ex -> {
                log.error("[MULTI] Interface {} failed: {}", seg.getInterfaceId(), ex.getMessage());
                return MultiInterfaceResult.InterfaceResultItem.failed(
                        seg.getInterfaceId(), seg.getInterfaceName(),
                        seg.getEndpoint(), ex.getMessage());
            }).orTimeout(10, TimeUnit.MINUTES).exceptionally(ex -> {
                log.warn("[MULTI] Interface {} timeout", seg.getInterfaceId());
                return MultiInterfaceResult.InterfaceResultItem.timeout(
                        seg.getInterfaceId(), seg.getInterfaceName(), seg.getEndpoint());
            }));
        }

        // 等待全部完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        // 聚合
        int success = 0, failed = 0;
        for (CompletableFuture<MultiInterfaceResult.InterfaceResultItem> f : futures) {
            MultiInterfaceResult.InterfaceResultItem item = f.getNow(null);
            if (item != null) {
                aggregate.getInterfaces().add(item);
                if ("SUCCESS".equals(item.getStatus())) success++;
                else failed++;
            }
        }
        aggregate.setSuccessCount(success);
        aggregate.setFailedCount(failed);

        // 全部失败 → 降级标记
        if (success == 0 && failed > 0) {
            log.warn("[MULTI] All interfaces failed — consider fallback to full-doc processing");
        }

        return aggregate;
    }

    private RequirementResult processOneInterface(InterfaceSegment seg, String providerCode,
                                                   String flowType, List<InterfaceSegment> allSegments,
                                                   int index, int total) {
        // 兄弟接口摘要（不含当前接口）
        List<InterfaceSegment> siblings = allSegments.stream()
                .filter(s -> !s.getInterfaceId().equals(seg.getInterfaceId()))
                .toList();

        // 构建独立 Prompt
        String prompt = promptBuilder.buildInterfacePrompt(seg, siblings, flowType, providerCode);

        // 调用 RequirementAgent
        RequirementResult result = requirementAgent.analyze(
                prompt, providerCode, flowType, null);

        // 注入接口元数据
        result.setInterfaceId(seg.getInterfaceId());
        result.setInterfaceName(seg.getInterfaceName());
        result.setInterfaceIndex(seg.getIndex());
        result.setTotalInterfaces(total);

        return result;
    }

    private MultiInterfaceResult.InterfaceResultItem buildItem(InterfaceSegment seg,
                                                                RequirementResult result) {
        if (result.getParseError() != null) {
            return MultiInterfaceResult.InterfaceResultItem.failed(
                    seg.getInterfaceId(), seg.getInterfaceName(),
                    seg.getEndpoint(), result.getParseError());
        }
        return MultiInterfaceResult.InterfaceResultItem.success(
                seg.getInterfaceId(), seg.getInterfaceName(),
                seg.getEndpoint(), result);
    }
}
