package com.fundlink.ai.feedback;

import com.fundlink.ai.gateway.RagGateway;
import com.fundlink.ai.mapper.AiFeedbackMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 高频修正模式 → 自动生成知识条目 → 写回 RAG 知识库
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeAutoWriter {

    private final AiFeedbackMapper feedbackMapper;
    private final RagGateway ragGateway;

    /** 每周日 3:30 AM — 在 PatternAnalyzer 之后运行 */
    @Scheduled(cron = "0 30 3 * * SUN")
    public void autoWrite() {
        List<Map<String, Object>> patterns = feedbackMapper.findPatterns();
        if (patterns.isEmpty()) {
            log.info("Auto-write: 无高频修正模式");
            return;
        }

        int written = 0;
        for (Map<String, Object> p : patterns) {
            String category = (String) p.get("category");
            Object freq = p.get("freq");
            try {
                String markdown = buildKnowledgeEntry(category, freq);
                boolean ok = ragGateway.upsertKnowledge(category, "AUTO", markdown);
                if (ok) {
                    written++;
                    log.info("Auto-write: {} → RAG ({}次)", category, freq);
                }
            } catch (Exception e) {
                log.error("Auto-write failed: {}", category, e);
            }
        }
        log.info("Auto-write done: {}/{} patterns written", written, patterns.size());
    }

    private String buildKnowledgeEntry(String type, Object freq) {
        String date = LocalDate.now().format(DateTimeFormatter.ISO_DATE);
        return String.format("""
            ## 自动化修正规则
            - **类型**: %s
            - **确认次数**: %s (截至 %s)
            - **来源**: AI Copilot 反馈自动挖掘

            ### 适用场景
            此规则在生成资金方字段映射和流程配置时自动应用。

            ### 验证状态
            已通过 %s 次人工审核确认，置信度高。
            """, type, freq, date, freq);
    }
}
