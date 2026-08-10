package com.fundlink.ai.controller;

import com.fundlink.ai.entity.AiTask;
import com.fundlink.ai.feedback.FeedbackCollector;
import com.fundlink.ai.mapper.AiTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 人工反馈 API — 排查结果评分 + 修正。
 * <p>
 * 反馈数据写入 ai_feedback，由 PatternAnalyzer / KnowledgeAutoWriter
 * 定时分析后写回 RAG 知识库。
 */
@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackCollector feedbackCollector;
    private final AiTaskMapper taskMapper;

    /**
     * 提交排查反馈。
     * <pre>
     * {
     *   "taskId": 123,
     *   "rating": "HELPFUL",        // HELPFUL | NOT_HELPFUL
     *   "correction": "实际是..."    // 可选，用户修正内容
     * }
     * </pre>
     */
    @PostMapping("/feedback")
    public Map<String, Object> submitFeedback(@RequestBody Map<String, Object> req) {
        Map<String, Object> result = new LinkedHashMap<>();

        Object taskIdObj = req.get("taskId");
        if (taskIdObj == null) {
            result.put("code", -1);
            result.put("msg", "缺少 taskId");
            return result;
        }
        Long taskId = Long.valueOf(taskIdObj.toString());
        String rating = req.getOrDefault("rating", "NO_RATING").toString();
        String correction = req.containsKey("correction") && req.get("correction") != null
                ? req.get("correction").toString() : null;

        // 读取原始诊断文本
        String aiSuggestion = "";
        String providerCode = null;
        try {
            AiTask task = taskMapper.selectById(taskId);
            if (task != null) {
                aiSuggestion = task.getOutputData() != null ? task.getOutputData() : "";
                providerCode = task.getProviderCode();
            }
        } catch (Exception e) {
            log.warn("[Feedback] Failed to read task {}: {}", taskId, e.getMessage());
        }

        feedbackCollector.collectRating(taskId, rating, correction, aiSuggestion, providerCode);

        result.put("code", 0);
        result.put("msg", "反馈已记录");
        result.put("taskId", taskId);
        return result;
    }
}
