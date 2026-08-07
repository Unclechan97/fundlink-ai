package com.fundlink.ai.feedback;

import com.fundlink.ai.mapper.AiFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PatternAnalyzer {

    private final AiFeedbackMapper feedbackMapper;

    @Scheduled(cron = "0 0 3 * * SUN")  // 每周日 3AM
    public void analyze() {
        List<Map<String, Object>> patterns = feedbackMapper.findPatterns();
        if (!patterns.isEmpty()) {
            log.info("=== 修正模式报告 ({} 个模式) ===", patterns.size());
            for (Map<String, Object> p : patterns) {
                String category = (String) p.get("category");
                Object freq = p.get("freq");
                log.info("  类型: {}, 频次: {}", category, freq);
            }
        } else {
            log.info("本周无高频修正模式");
        }
    }

    /** 手动触发分析 */
    public List<Map<String, Object>> analyzeNow() {
        return feedbackMapper.findPatterns();
    }
}
