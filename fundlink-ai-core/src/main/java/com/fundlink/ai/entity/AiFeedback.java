package com.fundlink.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("ai_feedback")
public class AiFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long taskId;
    private String feedbackType;
    private String aiSuggestion;
    private String humanResult;
    private String diffSummary;
    private String category;
    private String providerCode;
    private LocalDateTime createTime;
}
