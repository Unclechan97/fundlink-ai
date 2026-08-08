package com.fundlink.ai.controller;

import com.fundlink.ai.agent.loop.LoopEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSE event publisher for frontend streaming.
 */
@Slf4j
@Primary
@Component
public class SseLoopEventPublisher implements LoopEventPublisher {

    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long taskId) {
        SseEmitter emitter = new SseEmitter(10 * 60 * 1000L);
        emitter.onCompletion(() -> { emitters.remove(taskId); log.info("[SSE] Completed task={}", taskId); });
        emitter.onTimeout(() -> { emitters.remove(taskId); log.info("[SSE] Timeout task={}", taskId); });
        emitter.onError(e -> { emitters.remove(taskId); log.warn("[SSE] Error task={}: {}", taskId, e.getMessage()); });
        emitters.put(taskId, emitter);
        return emitter;
    }

    @Override
    public void phaseStart(Long taskId, String phase, int round, int maxRounds) {
        send(taskId, "phase:start", Map.of("phase", phase, "round", round, "maxRounds", maxRounds));
    }

    @Override
    public void phaseProgress(Long taskId, String phase, String message) {
        send(taskId, "phase:progress", Map.of("phase", phase, "message", message));
    }

    @Override
    public void phaseComplete(Long taskId, String phase, String summary) {
        send(taskId, "phase:complete", Map.of("phase", phase, "summary", summary));
    }

    @Override
    public void phaseError(Long taskId, String phase, String message) {
        send(taskId, "phase:error", Map.of("phase", phase, "message", message));
    }

    @Override
    public void decisionRequired(Long taskId, String type, String summary, List<String> options) {
        send(taskId, "decision_required", Map.of("type", type, "summary", summary, "options", options));
    }

    @Override
    public void taskComplete(Long taskId, String status, String summary) {
        send(taskId, "task:complete", Map.of("status", status, "summary", summary));
    }

    @Override
    public void taskFailed(Long taskId, String error, int rounds) {
        send(taskId, "task:failed", Map.of("status", "FAILED", "error", error, "rounds", rounds));
    }

    private void send(Long taskId, String eventName, Object data) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (IOException e) {
            log.warn("[SSE] Send failed task={}: {}", taskId, e.getMessage());
            emitters.remove(taskId);
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }
}
