package com.fundlink.ai.agent.loop;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 日志实现 — core 模块默认，测试和独立运行时使用
 */
@Slf4j
@Component
public class LoggingLoopEventPublisher implements LoopEventPublisher {

    @Override
    public void phaseStart(Long taskId, String phase, int round, int maxRounds) {
        log.info("[LOOP] phase:start  task={}  phase={}  round={}/{}", taskId, phase, round, maxRounds);
    }

    @Override
    public void phaseProgress(Long taskId, String phase, String message) {
        log.info("[LOOP] phase:progress  task={}  phase={}  msg={}", taskId, phase, message);
    }

    @Override
    public void phaseComplete(Long taskId, String phase, String summary) {
        log.info("[LOOP] phase:complete  task={}  phase={}  summary={}", taskId, phase, summary);
    }

    @Override
    public void phaseError(Long taskId, String phase, String message) {
        log.error("[LOOP] phase:error  task={}  phase={}  msg={}", taskId, phase, message);
    }

    @Override
    public void decisionRequired(Long taskId, String type, String summary, List<String> options) {
        log.warn("[LOOP] decision_required  task={}  type={}  summary={}  options={}",
                taskId, type, summary, options);
    }

    @Override
    public void taskComplete(Long taskId, String status, String summary) {
        log.info("[LOOP] task:complete  task={}  status={}  summary={}", taskId, status, summary);
    }

    @Override
    public void taskFailed(Long taskId, String error, int rounds) {
        log.error("[LOOP] task:failed  task={}  error={}  rounds={}", taskId, error, rounds);
    }
}
