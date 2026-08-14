package com.fundlink.ai.config;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.agent.loop.MultiLoopOrchestrator;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 启动补偿（B6.2）— 消除重启后的僵尸任务：
 * <ol>
 *   <li>中间态任务（RUNNING/ANALYZE/VALIDATE/DRYRUN/DIAGNOSE/DECISION_POINT/DIAGNOSING）
 *       批量标记 FAILED（原因"服务重启"）；PENDING 保持不变。
 *       终态后可通过 POST /api/ai/loop/{taskId}/retry 重置重启。</li>
 *   <li>MULTI_LOOP 父任务若子任务全部终态则聚合状态 — 兜底重启打断的内存监控线程。</li>
 * </ol>
 */
@Slf4j
@Component
public class LoopStartupRecovery {

    private static final Set<String> INTERMEDIATE_STATUSES = Set.of(
            "RUNNING", "ANALYZE", "VALIDATE", "DRYRUN", "DIAGNOSE", "DECISION_POINT", "DIAGNOSING");

    private final AiTaskMapper taskMapper;
    private final MultiLoopOrchestrator multiLoopOrchestrator;
    private final ObjectMapper json = new ObjectMapper();

    public LoopStartupRecovery(AiTaskMapper taskMapper, MultiLoopOrchestrator multiLoopOrchestrator) {
        this.taskMapper = taskMapper;
        this.multiLoopOrchestrator = multiLoopOrchestrator;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        try {
            List<AiTask> zombies = taskMapper.selectList(
                    Wrappers.<AiTask>lambdaQuery().in(AiTask::getStatus, INTERMEDIATE_STATUSES));
            for (AiTask t : zombies) {
                String old = t.getStatus();
                t.setStatus("FAILED");
                try {
                    t.setOutputData(json.writeValueAsString(Map.of(
                            "error", "服务重启",
                            "failedAt", LocalDateTime.now().toString())));
                } catch (Exception ignored) {}
                t.setUpdateTime(LocalDateTime.now());
                taskMapper.updateById(t);
                log.warn("[RECOVERY] Task {} ({}) {} → FAILED — 服务重启", t.getId(), t.getTaskNo(), old);
            }
            if (!zombies.isEmpty()) {
                log.warn("[RECOVERY] {} zombie task(s) marked FAILED — 可经 /api/ai/loop/{id}/retry 恢复",
                        zombies.size());
            }

            // MULTI_LOOP 父任务聚合兜底
            List<AiTask> parents = taskMapper.selectList(Wrappers.<AiTask>lambdaQuery()
                    .eq(AiTask::getTaskType, "MULTI_LOOP")
                    .eq(AiTask::getStatus, "PENDING"));
            for (AiTask p : parents) {
                multiLoopOrchestrator.aggregateParentStatus(p.getId());
            }
        } catch (Exception e) {
            log.error("[RECOVERY] Startup recovery failed: {}", e.getMessage(), e);
        }
    }
}
