package com.fundlink.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 闭环任务 — Agent Loop 核心状态载体
 */
@Data
@TableName("ai_task")
public class AiTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务编号 (LOOP-xxx) */
    private String taskNo;

    /** 任务类型: REQUIREMENT / LOOP */
    private String taskType;

    /** 状态: PENDING / ANALYZE / VALIDATE / DRYRUN / DIAGNOSE / DECISION_POINT / PUBLISHED / FAILED / ABORTED */
    private String status;

    /** 当前重试轮次 */
    private Integer currentRound;

    /** 最大重试轮次 */
    private Integer maxRounds;

    /** 流程类型: LOAN / CREDIT / REPAY */
    private String flowType;

    /** 资金方编码 */
    private String providerCode;

    /** 输入接口文档原文 */
    private String documentText;

    /** 输入数据 (JSON) */
    private String inputData;

    /** 输出数据 (JSON) */
    private String outputData;

    /** 当前轮次结果快照 (JSON — RequirementResult 序列化) */
    private String currentResult;

    /** 关联 Trace ID */
    private String traceId;

    /** 父任务 ID（多接口闭环时子任务指向主任务，单接口为 NULL） */
    private Long parentTaskId;

    /** 接口标识（如 LOAN_APPLY，子任务有值，主任务为 NULL） */
    private String interfaceId;

    /** 接口名称（如 "放款申请"） */
    private String interfaceName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
