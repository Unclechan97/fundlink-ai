package com.fundlink.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 执行 Trace — 记录每轮每阶段的执行详情
 */
@Data
@TableName("ai_agent_trace")
public class AiAgentTrace {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** Trace 唯一标识 */
    private String traceId;

    /** 关联任务 ID */
    private Long taskId;

    /** 阶段: ANALYZE / VALIDATE / DRYRUN / DIAGNOSE */
    private String phase;

    /** Agent 名称 */
    private String agentName;

    /** Agent 类型: requirement / testgen / diagnosis */
    private String agentType;

    /** 步骤名称 */
    private String stepName;

    /** 输入摘要 */
    private String inputSummary;

    /** 输出摘要 */
    private String outputSummary;

    /** 输入全文 */
    private String inputText;

    /** 输出全文 */
    private String outputText;

    /** 工具调用记录 (JSON) */
    private String toolCalls;

    /** Token 用量 (JSON: {input, output, total}) */
    private String tokenUsage;

    /** 延迟 (ms) */
    private Integer latencyMs;

    /** 执行耗时 (ms) */
    private Integer durationMs;

    /** 状态: RUNNING / SUCCESS / FAILED */
    private String status;

    /** 是否成功: 1=成功 0=失败 */
    private Integer success;

    /** 错误信息 */
    private String errorMsg;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
