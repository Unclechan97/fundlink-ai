package com.fundlink.ai.agent.loop;

import java.util.List;

/**
 * Loop 事件发布器 — SSE 事件协议 (设计 §6)
 * <p>
 * core 模块接口，app 模块通过 SseEmitter 实现。
 * core 内置 LoggingLoopEventPublisher 作为默认日志实现。
 */
public interface LoopEventPublisher {

    void phaseStart(Long taskId, String phase, int round, int maxRounds);

    void phaseProgress(Long taskId, String phase, String message);

    void phaseComplete(Long taskId, String phase, String summary);

    void phaseError(Long taskId, String phase, String message);

    void decisionRequired(Long taskId, String type, String summary, List<String> options);

    void taskComplete(Long taskId, String status, String summary);

    void taskFailed(Long taskId, String error, int rounds);
}
