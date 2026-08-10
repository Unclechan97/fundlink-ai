package com.fundlink.ai.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundlink.ai.entity.AiFeedback;
import com.fundlink.ai.mapper.AiFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackCollector {

    private final AiFeedbackMapper mapper;
    private final ObjectMapper json = new ObjectMapper();

    @Async
    public void collect(Long taskId, String type, String aiSuggestion,
                        String humanResult, String providerCode) {
        try {
            AiFeedback fb = new AiFeedback();
            fb.setTaskId(taskId);
            fb.setFeedbackType(type);
            fb.setAiSuggestion(aiSuggestion);
            fb.setHumanResult(humanResult);
            fb.setDiffSummary("AI建议与人工结果差异已记录");
            fb.setProviderCode(providerCode);
            fb.setCreateTime(LocalDateTime.now());
            mapper.insert(fb);
            log.info("Feedback recorded: task={}, type={}", taskId, type);
        } catch (Exception e) {
            log.error("Failed to save feedback", e);
        }
    }

    /**
     * 排查反馈 — 用户对排查结果评分（踩/赞）+ 可选修正。
     *
     * @param taskId       排查任务 ID
     * @param rating       评分: "HELPFUL" / "NOT_HELPFUL"
     * @param correction   用户手动修正内容（可为 null）
     * @param aiSuggestion AI 原始诊断文本
     * @param providerCode 资金方编码
     */
    @Async
    public void collectRating(Long taskId, String rating, String correction,
                               String aiSuggestion, String providerCode) {
        try {
            AiFeedback fb = new AiFeedback();
            fb.setTaskId(taskId);
            fb.setFeedbackType("TROUBLESHOOT_RATING");
            fb.setAiSuggestion(aiSuggestion != null ? aiSuggestion : "");
            fb.setHumanResult(rating != null ? rating : "NO_RATING");
            fb.setDiffSummary(correction != null ? correction : "");
            fb.setCategory(rating != null ? rating : "NO_RATING");
            fb.setProviderCode(providerCode);
            fb.setCreateTime(LocalDateTime.now());
            mapper.insert(fb);
            log.info("Troubleshoot feedback recorded: task={}, rating={}, hasCorrection={}",
                    taskId, rating, correction != null && !correction.isBlank());
        } catch (Exception e) {
            log.error("Failed to save troubleshoot feedback", e);
        }
    }
}
