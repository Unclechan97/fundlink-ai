package com.fundlink.ai.agent.loop;

import com.fundlink.ai.agent.FlowTypeDetector;
import com.fundlink.ai.agent.PromptBuilder;
import com.fundlink.ai.agent.split.DocumentSplitter;
import com.fundlink.ai.agent.split.EndpointShortName;
import com.fundlink.ai.agent.split.InterfaceDeduplicator;
import com.fundlink.ai.agent.split.InterfaceSegment;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 多接口自动闭环编排器。
 *
 * 将一个包含多个接口的文档拆分为 N 个子任务，每个子任务独立走完整闭环。
 * 复用 {@link AgentLoopOrchestrator#start(Long)} 驱动子任务。
 */
@Slf4j
@Service
public class MultiLoopOrchestrator {

    private final AgentLoopOrchestrator loopOrchestrator;
    private final AiTaskMapper taskMapper;
    private final PromptBuilder promptBuilder;

    public MultiLoopOrchestrator(AgentLoopOrchestrator loopOrchestrator,
                                  AiTaskMapper taskMapper,
                                  PromptBuilder promptBuilder) {
        this.loopOrchestrator = loopOrchestrator;
        this.taskMapper = taskMapper;
        this.promptBuilder = promptBuilder;
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

            // 异步启动子任务闭环
            CompletableFuture.runAsync(() -> {
                try {
                    loopOrchestrator.start(sub.getId());
                } catch (Exception e) {
                    log.error("[MULTI] Sub-task {} start failed: {}", sub.getId(), e.getMessage(), e);
                }
            });

            log.info("[MULTI] Sub-task created  id={}  interfaceId={}  name={}",
                    sub.getId(), segment.getInterfaceId(), segment.getInterfaceName());
        }

        log.info("[MULTI] Multi-loop ready  parentId={}  subTaskCount={}", parent.getId(), subTasks.size());
        return new MultiLoopResult(parent.getId(), parent.getTaskNo(), subTasks);
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
