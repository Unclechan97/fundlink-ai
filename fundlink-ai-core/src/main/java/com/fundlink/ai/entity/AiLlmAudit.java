package com.fundlink.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LLM 调用审计日志 — 金融合规核心表
 */
@Data
@TableName("ai_llm_audit")
public class AiLlmAudit {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 调用唯一ID */
    private String callId;

    /** 模型提供商: ANTHROPIC/OPENAI/QWEN/DEEPSEEK */
    private String provider;

    /** 模型名称 */
    private String model;

    /** 输入 Token 数 */
    private Integer tokenInput;

    /** 输出 Token 数 */
    private Integer tokenOutput;

    /** 费用(USD) */
    private BigDecimal costAmount;

    /** 延迟(ms) */
    private Integer latencyMs;

    /** 是否成功: 1=成功 0=失败 */
    private Integer success;

    /** 错误信息 */
    private String errorMsg;

    /** 关联 Trace ID */
    private String traceId;

    /** 创建时间 */
    private LocalDateTime createTime;
}
