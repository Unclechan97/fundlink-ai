package com.fundlink.ai.agent.loop;

import java.util.List;
import java.util.Map;

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

    // ═══════════════════════════════════════════════════════════
    // Phase 3: 多接口事件
    // ═══════════════════════════════════════════════════════════

    /** 开始拆分 */
    default void splitStart(Long taskId) {}

    /** 拆分完成 */
    default void splitComplete(Long taskId, int totalCount,
                                List<Map<String, Object>> interfaces) {}

    /** 单个接口开始处理 */
    default void interfaceStart(Long taskId, String interfaceId, String name,
                                 int index, int total) {}

    /** 单个接口阶段开始 */
    default void interfacePhaseStart(Long taskId, String interfaceId,
                                      String phase, int round, int maxRounds) {}

    /** 单个接口阶段进度 */
    default void interfacePhaseProgress(Long taskId, String interfaceId,
                                         String phase, String message) {}

    /** 单个接口阶段完成 */
    default void interfacePhaseComplete(Long taskId, String interfaceId,
                                         String phase, String summary) {}

    /** 单个接口阶段错误 */
    default void interfacePhaseError(Long taskId, String interfaceId,
                                      String phase, String message) {}

    /** 单个接口处理完成 */
    default void interfaceComplete(Long taskId, String interfaceId, String name,
                                    String status, String summary) {}

    /** 全部接口处理完成 */
    default void allComplete(Long taskId, int totalCount, int successCount,
                              int failedCount) {}
}
