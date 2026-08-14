package com.fundlink.ai.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.FlowTypeDetector;
import com.fundlink.ai.agent.PromptBuilder;
import com.fundlink.ai.agent.split.DocumentSplitter;
import com.fundlink.ai.agent.split.InterfaceDeduplicator;
import com.fundlink.ai.agent.split.InterfaceSegment;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.mapper.AiTaskMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 多接口自动闭环编排器。
 *
 * 将一个包含多个接口的文档拆分为 N 个子任务，每个子任务独立走完整闭环。
 * 复用 {@link AgentLoopOrchestrator#start(Long)} 驱动子任务（@Async，调用即返回）。
 * <p>
 * 父任务状态聚合：后台监控线程轮询子任务，全部到达终态后父任务标记
 * PUBLISHED（全部成功）/ FAILED（任一失败），汇总写入 output_data。
 * SSE 已移除（2026-08），聚合逻辑与前端轮询无耦合。
 */
@Slf4j
@Service
public class MultiLoopOrchestrator {

    private static final Set<String> SUB_TERMINAL = Set.of("PUBLISHED", "FAILED", "ABORTED");
    /** 聚合监控轮询间隔（ms） */
    private static final long MONITOR_POLL_MS = 3000;
    /** 聚合监控最长时长 — 超过后放弃（留给重启补偿兜底） */
    private static final long MONITOR_MAX_MS = 12 * 3600_000L;

    private final AgentLoopOrchestrator loopOrchestrator;
    private final AiTaskMapper taskMapper;
    private final PromptBuilder promptBuilder;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService monitorExecutor;

    public MultiLoopOrchestrator(AgentLoopOrchestrator loopOrchestrator,
                                  AiTaskMapper taskMapper,
                                  PromptBuilder promptBuilder) {
        this.loopOrchestrator = loopOrchestrator;
        this.taskMapper = taskMapper;
        this.promptBuilder = promptBuilder;
        AtomicInteger seq = new AtomicInteger(0);
        this.monitorExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "multi-loop-monitor-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void shutdown() {
        monitorExecutor.shutdownNow();
    }

    /**
     * 创建多接口闭环任务。
     *
     * @param documentText          原始文档全文
     * @param providerCode          资金方编码
     * @param flowType              流程类型（为空时自动检测）
     * @param selectedInterfaceIds  用户勾选的接口 ID 列表
     * @param maxRounds             每个子任务最大重试轮次
     * @return 主任务信息 + 子任务列表
     */
    public MultiLoopResult createMultiLoop(String documentText, String providerCode,
                                            String flowType, List<String> selectedInterfaceIds,
                                            int maxRounds) {
        String ft = flowType != null && !flowType.isBlank()
                ? flowType.toUpperCase()
                : FlowTypeDetector.detect(documentText, null);

        // 1. 拆分文档
        DocumentSplitter splitter = new DocumentSplitter(new InterfaceDeduplicator());
        List<InterfaceSegment> allSegments = splitter.split(documentText);

        // 2. 按用户勾选过滤
        Set<String> selectedSet = new HashSet<>(selectedInterfaceIds);
        List<InterfaceSegment> selected = allSegments.stream()
                .filter(s -> selectedSet.contains(s.getInterfaceId()))
                .toList();

        if (selected.isEmpty()) {
            throw new IllegalArgumentException("未找到选中的接口: " + selectedInterfaceIds);
        }

        log.info("[MULTI] Creating multi-loop  provider={}  selected={}/{}  maxRounds={}",
                providerCode, selected.size(), allSegments.size(), maxRounds);

        // 3. 创建父任务
        AiTask parent = new AiTask();
        parent.setTaskNo("MULTI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        parent.setTaskType("MULTI_LOOP");
        parent.setStatus("PENDING");
        parent.setFlowType(ft);
        parent.setProviderCode(providerCode);
        parent.setDocumentText(documentText);
        parent.setCurrentRound(0);
        parent.setMaxRounds(maxRounds);
        parent.setCreateTime(LocalDateTime.now());
        parent.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(parent);
        log.info("[MULTI] Parent task created  id={}  taskNo={}", parent.getId(), parent.getTaskNo());

        // 4. 为每个选中接口创建子任务并启动
        List<SubTaskInfo> subTasks = new ArrayList<>();
        List<Long> subTaskIds = new ArrayList<>();
        for (InterfaceSegment segment : selected) {
            AiTask sub = new AiTask();
            sub.setParentTaskId(parent.getId());
            sub.setInterfaceId(segment.getInterfaceId());
            sub.setInterfaceName(segment.getInterfaceName());
            sub.setTaskNo("LOOP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            sub.setTaskType("LOOP");
            sub.setStatus("PENDING");
            sub.setFlowType(segment.getFlowType() != null ? segment.getFlowType() : ft);
            sub.setProviderCode(providerCode);

            // 使用 buildInterfacePrompt 构建带上下文的提示词作为 documentText
            String prompt = promptBuilder.buildInterfacePrompt(
                    segment, allSegments, ft, providerCode);
            sub.setDocumentText(prompt);
            sub.setCurrentRound(0);
            sub.setMaxRounds(maxRounds);
            sub.setCreateTime(LocalDateTime.now());
            sub.setUpdateTime(LocalDateTime.now());
            taskMapper.insert(sub);

            subTasks.add(new SubTaskInfo(sub.getId(), segment.getInterfaceId(),
                    segment.getInterfaceName(), segment.getEndpoint()));
            subTaskIds.add(sub.getId());

            // 直接调用（start 本身 @Async）— 不再经 commonPool 的 runAsync
            try {
                loopOrchestrator.start(sub.getId());
            } catch (Exception e) {
                log.error("[MULTI] Sub-task {} start failed: {}", sub.getId(), e.getMessage(), e);
            }

            log.info("[MULTI] Sub-task created  id={}  interfaceId={}  name={}",
                    sub.getId(), segment.getInterfaceId(), segment.getInterfaceName());
        }

        // 5. 后台聚合：子任务全部终态后父任务标记 PUBLISHED/FAILED
        monitorExecutor.submit(() -> monitorParentUntilTerminal(parent.getId(), subTaskIds));

        log.info("[MULTI] Multi-loop ready  parentId={}  subTaskCount={}", parent.getId(), subTasks.size());
        return new MultiLoopResult(parent.getId(), parent.getTaskNo(), subTasks);
    }

    /** 轮询子任务直至全部终态（或超时放弃），然后聚合父任务状态 */
    private void monitorParentUntilTerminal(Long parentId, List<Long> subTaskIds) {
        long deadline = System.currentTimeMillis() + MONITOR_MAX_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(MONITOR_POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (allSubTasksTerminal(subTaskIds)) {
                aggregateParentStatus(parentId);
                return;
            }
        }
        log.warn("[MULTI] Parent {} monitor gave up after {}h — 等待重启补偿兜底",
                parentId, MONITOR_MAX_MS / 3600_000L);
    }

    private boolean allSubTasksTerminal(List<Long> subTaskIds) {
        for (Long id : subTaskIds) {
            AiTask t = taskMapper.selectById(id);
            if (t == null || !SUB_TERMINAL.contains(t.getStatus())) return false;
        }
        return true;
    }

    /** 汇总子任务状态写入父任务：全部 PUBLISHED → PUBLISHED，否则 FAILED。幂等。 */
    public void aggregateParentStatus(Long parentId) {
        try {
            AiTask parent = taskMapper.selectById(parentId);
            if (parent == null) return;
            if ("PUBLISHED".equals(parent.getStatus()) || "FAILED".equals(parent.getStatus())) {
                return; // 已聚合
            }

            List<AiTask> children = taskMapper.selectList(
                    com.baomidou.mybatisplus.core.toolkit.Wrappers.<AiTask>lambdaQuery()
                            .eq(AiTask::getParentTaskId, parentId));
            if (children.isEmpty() || children.stream().anyMatch(c -> !SUB_TERMINAL.contains(c.getStatus()))) {
                return; // 还有未完成的子任务
            }

            int published = (int) children.stream().filter(c -> "PUBLISHED".equals(c.getStatus())).count();
            int failed = (int) children.stream().filter(c -> "FAILED".equals(c.getStatus())).count();
            int aborted = (int) children.stream().filter(c -> "ABORTED".equals(c.getStatus())).count();
            String parentStatus = (published == children.size()) ? "PUBLISHED" : "FAILED";

            parent.setStatus(parentStatus);
            parent.setUpdateTime(LocalDateTime.now());
            try {
                parent.setOutputData(json.writeValueAsString(Map.of(
                        "total", children.size(),
                        "published", published,
                        "failed", failed,
                        "aborted", aborted,
                        "aggregatedAt", LocalDateTime.now().toString())));
            } catch (Exception ignored) {}
            taskMapper.updateById(parent);
            log.info("[MULTI] Parent {} aggregated  status={}  total={}  published={}  failed={}  aborted={}",
                    parentId, parentStatus, children.size(), published, failed, aborted);
        } catch (Exception e) {
            log.error("[MULTI] Failed to aggregate parent {}: {}", parentId, e.getMessage());
        }
    }

    // ── 结果类型 ──

    public static class MultiLoopResult {
        private final Long parentTaskId;
        private final String parentTaskNo;
        private final List<SubTaskInfo> subTasks;

        public MultiLoopResult(Long parentTaskId, String parentTaskNo, List<SubTaskInfo> subTasks) {
            this.parentTaskId = parentTaskId;
            this.parentTaskNo = parentTaskNo;
            this.subTasks = subTasks;
        }

        public Long getParentTaskId() { return parentTaskId; }
        public String getParentTaskNo() { return parentTaskNo; }
        public List<SubTaskInfo> getSubTasks() { return subTasks; }
    }

    public static class SubTaskInfo {
        private final Long taskId;
        private final String interfaceId;
        private final String interfaceName;
        private final String endpoint;

        public SubTaskInfo(Long taskId, String interfaceId, String interfaceName, String endpoint) {
            this.taskId = taskId;
            this.interfaceId = interfaceId;
            this.interfaceName = interfaceName;
            this.endpoint = endpoint;
        }

        public Long getTaskId() { return taskId; }
        public String getInterfaceId() { return interfaceId; }
        public String getInterfaceName() { return interfaceName; }
        public String getEndpoint() { return endpoint; }
    }
}
