package com.fundlink.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fundlink.ai.entity.AiFeedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiFeedbackMapper extends BaseMapper<AiFeedback> {

    /** 聚合修正模式: 同一字段被修正≥3次 */
    @Select("SELECT category, COUNT(*) as freq FROM ai_feedback " +
            "WHERE create_time > DATE_SUB(NOW(), INTERVAL 30 DAY) " +
            "GROUP BY category HAVING freq >= 3 ORDER BY freq DESC")
    List<Map<String, Object>> findPatterns();
}
