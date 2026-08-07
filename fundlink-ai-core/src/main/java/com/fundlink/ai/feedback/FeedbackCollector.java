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
}
