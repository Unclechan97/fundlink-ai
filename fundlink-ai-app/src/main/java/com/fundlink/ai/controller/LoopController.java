package com.fundlink.ai.controller;

import com.fundlink.ai.agent.FlowTypeDetector;
import com.fundlink.ai.agent.loop.AgentLoopOrchestrator;
import com.fundlink.ai.agent.loop.DecisionRequest;
import com.fundlink.ai.agent.loop.MultiLoopOrchestrator;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent Loop REST API — SSE 闭环控制 (设计 §6)
 */
@Slf4j
@RestController
@RequestMapping("/api/ai/loop")
public class LoopController {

    private final AgentLoopOrchestrator orchestrator;
    private final MultiLoopOrchestrator multiOrchestrator;
    private final SseLoopEventPublisher ssePublisher;
    private final AiTaskMapper taskMapper;

    public LoopController(AgentLoopOrchestrator orchestrator,
                          MultiLoopOrchestrator multiOrchestrator,
                          SseLoopEventPublisher ssePublisher,
                          AiTaskMapper taskMapper) {
        this.orchestrator = orchestrator;
        this.multiOrchestrator = multiOrchestrator;
        this.ssePublisher = ssePublisher;
        this.taskMapper = taskMapper;
    }

    /** Create loop task and start async execution */
    @PostMapping
    public CopilotController.ApiAiResponse<Map<String, Object>> create(@RequestBody CreateLoopRequest req) {
        AiTask task = new AiTask();
        task.setTaskNo("LOOP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        task.setTaskType("LOOP");
        task.setStatus("PENDING");
        task.setFlowType(req.getFlowType() != null && !req.getFlowType().isBlank()
                ? req.getFlowType().toUpperCase()
                : FlowTypeDetector.detect(req.getDocumentText(), null));
        task.setProviderCode(req.getProviderCode());
        task.setDocumentText(req.getDocumentText());
        task.setCurrentRound(0);
        task.setMaxRounds(req.getMaxRounds() != null ? req.getMaxRounds() : 3);
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

    /** Create multi-loop task: split doc → create parent + N sub-tasks → start each */
    @PostMapping("/multi")
    public CopilotController.ApiAiResponse<Map<String, Object>> createMulti(
            @RequestBody CreateMultiLoopRequest req) {
        MultiLoopOrchestrator.MultiLoopResult result = multiOrchestrator.createMultiLoop(
                req.getDocumentText(),
                req.getProviderCode(),
                req.getFlowType(),
                req.getSelectedInterfaceIds(),
                req.getMaxRounds() != null ? req.getMaxRounds() : 3);

        List<Map<String, Object>> subTaskList = result.getSubTasks().stream()
                .map(st -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("taskId", st.getTaskId());
                    m.put("interfaceId", st.getInterfaceId());
                    m.put("interfaceName", st.getInterfaceName());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parentTaskId", result.getParentTaskId());
        data.put("parentTaskNo", result.getParentTaskNo());
        data.put("subTasks", subTaskList);

        return CopilotController.ApiAiResponse.success(data);
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

    /** 用户中断正在执行的任务 */
    @PostMapping("/{taskId}/cancel")
    public CopilotController.ApiAiResponse<Map<String, Object>> cancel(@PathVariable Long taskId) {
        orchestrator.cancel(taskId);
        return CopilotController.ApiAiResponse.success(Map.of("taskId", taskId, "status", "CANCELLED"));
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

    /** Get current analysis result for editing */
    @GetMapping("/{taskId}/result")
    public CopilotController.ApiAiResponse<Object> getResult(@PathVariable Long taskId) {
        AiTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return CopilotController.ApiAiResponse.error("Task not found", null);
        }
        if (task.getCurrentResult() == null) {
            return CopilotController.ApiAiResponse.error("No result available", null);
        }
        try {
            Object result = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(task.getCurrentResult(), Object.class);
            return CopilotController.ApiAiResponse.success(result);
        } catch (Exception e) {
            return CopilotController.ApiAiResponse.error("Failed to parse result: " + e.getMessage(), null);
        }
    }

    // -- DTOs --

    public static class CreateMultiLoopRequest {
        private String documentText;
        private String providerCode;
        private String flowType;
        private List<String> selectedInterfaceIds;
        private Integer maxRounds;

        public String getDocumentText() { return documentText; }
        public void setDocumentText(String d) { this.documentText = d; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String p) { this.providerCode = p; }
        public String getFlowType() { return flowType; }
        public void setFlowType(String f) { this.flowType = f; }
        public List<String> getSelectedInterfaceIds() { return selectedInterfaceIds; }
        public void setSelectedInterfaceIds(List<String> ids) { this.selectedInterfaceIds = ids; }
        public Integer getMaxRounds() { return maxRounds; }
        public void setMaxRounds(Integer r) { this.maxRounds = r; }
    }

    public static class CreateLoopRequest {
        private String documentText;
        private String providerCode;
        private String flowType;
        private Integer maxRounds;

        public String getDocumentText() { return documentText; }
        public void setDocumentText(String d) { this.documentText = d; }
        public String getProviderCode() { return providerCode; }
        public void setProviderCode(String p) { this.providerCode = p; }
        public String getFlowType() { return flowType; }
        public void setFlowType(String f) { this.flowType = f; }
        public Integer getMaxRounds() { return maxRounds; }
        public void setMaxRounds(Integer r) { this.maxRounds = r; }
    }
}
