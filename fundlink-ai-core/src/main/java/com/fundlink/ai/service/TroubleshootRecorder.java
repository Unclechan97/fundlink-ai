package com.fundlink.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.constant.TaskStatus;
import com.fundlink.ai.constant.TaskType;
import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.mapper.AiTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 排查任务记录器 — 管理 DIAGNOSIS 类型 ai_task 的生命周期。
 * <p>
 * 排查流程：createTask → markDiagnosing → markCompleted / markFailed
 */
@Slf4j
@Service
public class TroubleshootRecorder {

    private final AiTaskMapper taskMapper;
    private final ObjectMapper json = new ObjectMapper();

    public TroubleshootRecorder(AiTaskMapper taskMapper) {
        this.taskMapper = taskMapper;
    }

    /**
     * 创建排查任务，状态 PENDING。
     *
     * @param errorLog     用户输入的报错日志
     * @param providerCode 资金方编码（可为 null）
     * @param traceId      请求链路 ID
     * @return 新创建的 AiTask（含自增 ID）
     */
    public AiTask createTask(String errorLog, String providerCode, String traceId) {
        AiTask task = new AiTask();
        task.setTaskNo("DIAG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        task.setTaskType(TaskType.DIAGNOSIS);
        task.setStatus(TaskStatus.PENDING);
        task.setDocumentText(errorLog);
        task.setProviderCode(providerCode);
        task.setTraceId(traceId);
        task.setCurrentRound(0);
        task.setMaxRounds(3);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());

        // 输入数据打包
        try {
            task.setInputData(json.writeValueAsString(Map.of(
                    "errorLog", errorLog != null ? errorLog : "",
                    "providerCode", providerCode != null ? providerCode : ""
            )));
        } catch (JsonProcessingException e) {
            log.warn("[TroubleshootRecorder] Failed to serialize inputData: {}", e.getMessage());
        }

        taskMapper.insert(task);
        log.info("[TroubleshootRecorder] Task created  id={}  taskNo={}  traceId={}",
                task.getId(), task.getTaskNo(), traceId);
        return task;
    }

    /**
     * 标记开始诊断。
     */
    public void markDiagnosing(Long taskId) {
        AiTask task = new AiTask();
        task.setId(taskId);
        task.setStatus(TaskStatus.DIAGNOSING);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
        log.info("[TroubleshootRecorder] DIAGNOSING  taskId={}", taskId);
    }

    /**
     * 标记诊断完成，写入诊断结果。
     *
     * @param taskId   任务 ID
     * @param analysis LLM 诊断文本
     * @param ragCount RAG 检索到的历史案例数
     * @param toolRounds Tool Calling 实际轮数
     */
    public void markCompleted(Long taskId, String analysis, int ragCount, int toolRounds) {
        AiTask task = new AiTask();
        task.setId(taskId);
        task.setStatus(TaskStatus.COMPLETED);
        task.setUpdateTime(LocalDateTime.now());

        try {
            task.setOutputData(json.writeValueAsString(Map.of(
                    "analysis", analysis != null ? analysis : "",
                    "ragExampleCount", ragCount,
                    "toolCallRounds", toolRounds,
                    "completedAt", LocalDateTime.now().toString()
            )));
        } catch (JsonProcessingException e) {
            log.warn("[TroubleshootRecorder] Failed to serialize outputData: {}", e.getMessage());
        }

        taskMapper.updateById(task);
        log.info("[TroubleshootRecorder] COMPLETED  taskId={}  analysisLen={}  ragRounds={}  toolRounds={}",
                taskId, analysis != null ? analysis.length() : 0, ragCount, toolRounds);
    }

    /**
     * 标记诊断失败。
     */
    public void markFailed(Long taskId, String error) {
        AiTask task = new AiTask();
        task.setId(taskId);
        task.setStatus(TaskStatus.FAILED);
        task.setUpdateTime(LocalDateTime.now());

        try {
            task.setOutputData(json.writeValueAsString(Map.of(
                    "error", error != null ? error : "未知错误",
                    "failedAt", LocalDateTime.now().toString()
            )));
        } catch (JsonProcessingException e) {
            log.warn("[TroubleshootRecorder] Failed to serialize outputData: {}", e.getMessage());
        }

        taskMapper.updateById(task);
        log.info("[TroubleshootRecorder] FAILED  taskId={}  error={}", taskId, error);
    }
}
