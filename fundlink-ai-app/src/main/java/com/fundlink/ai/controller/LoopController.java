package com.fundlink.ai.controller;

import com.fundlink.ai.agent.loop.AgentLoopOrchestrator;
import com.fundlink.ai.agent.loop.DecisionRequest;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.mapper.AiTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Agent Loop REST API — SSE 闭环控制 (设计 §6)
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/loop")
@RequiredArgsConstructor
public class LoopController {

    private final AgentLoopOrchestrator orchestrator;
    private final SseLoopEventPublisher ssePublisher;
    private final AiTaskMapper taskMapper;

    /** Create loop task and start async execution */
    @PostMapping
    public CopilotController.ApiAiResponse<Map<String, Object>> create(@RequestBody CreateLoopRequest req) {
        AiTask task = new AiTask();
        task.setTaskNo("LOOP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        task.setTaskType("LOOP");
        task.setStatus("PENDING");
        task.setFlowType(req.getFlowType() != null ? req.getFlowType() : "LOAN");
        task.setProviderCode(req.getProviderCode());
        task.setDocumentText(req.getDocumentText());
        task.setCurrentRound(0);
        task.setMaxRounds(3);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);

        log.info("[LOOP] Task created  id={}  taskNo={}  provider={}",
                task.getId(), task.getTaskNo(), task.getProviderCode());

        // Start asynchronously — SSE emitter registered via /stream endpoint
        return CopilotController.ApiAiResponse.success(Map.of(
                "taskId", task.getId(),
                "taskNo", task.getTaskNo()
        ));
    }

    /** SSE stream endpoint — register emitter, then start orchestrator */
    @GetMapping("/{taskId}/stream")
    public SseEmitter stream(@PathVariable Long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }

        SseEmitter emitter = ssePublisher.register(taskId);

        // Start orchestrator asynchronously
        if ("PENDING".equals(task.getStatus())) {
            orchestrator.start(taskId);
        }

        return emitter;
    }

    /** Human decision */
    @PostMapping("/{taskId}/decide")
    public CopilotController.ApiAiResponse<Map<String, Object>> decide(
            @PathVariable Long taskId, @RequestBody DecisionRequest req) {
        req.setTaskId(taskId);
        orchestrator.decide(taskId, req);
        return CopilotController.ApiAiResponse.success(Map.of("taskId", taskId, "decision", req.getDecision()));
    }

    /** Task status query */
    @GetMapping("/{taskId}")
    public CopilotController.ApiAiResponse<Map<String, Object>> status(@PathVariable Long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return CopilotController.ApiAiResponse.error("Task not found", null);
        }
        return CopilotController.ApiAiResponse.success(Map.of(
                "taskId", task.getId(),
                "taskNo", task.getTaskNo(),
                "status", task.getStatus(),
                "currentRound", task.getCurrentRound(),
                "maxRounds", task.getMaxRounds(),
                "providerCode", task.getProviderCode(),
                "flowType", task.getFlowType()
        ));
    }

    // -- DTO --

    public static class CreateLoopRequest {
        private String documentText;
        private String providerCode;
        private String flowType;

        public String getDocumentText() { return documentText; }
        public void setDocumentText(String d) { this.documentText = d; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String p) { this.providerCode = p; }
        public String getFlowType() { return flowType; }
        public void setFlowType(String f) { this.flowType = f; }
    }
}
