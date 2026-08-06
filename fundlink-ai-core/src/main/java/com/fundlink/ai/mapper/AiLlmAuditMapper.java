package com.fundlink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fundlink.ai.entity.AiLlmAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * LLM 审计日志 Mapper
 */
@Mapper
public interface AiLlmAuditMapper extends BaseMapper<AiLlmAudit> {
}
