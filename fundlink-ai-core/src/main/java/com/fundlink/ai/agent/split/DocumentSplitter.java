package com.fundlink.ai.agent.split;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 文档拆分器。
 *
 * 策略链：4 级降级，第一个成功即返回。
 * 1. MarkdownHeadingStrategy — 标题匹配
 * 2. DelimiterStrategy — 分隔线匹配
 * 3. AnchorStrategy — 端点锚点匹配
 * 4. FullDocStrategy — 全文档 = 单接口（兜底）
 *
 * 拆分完成后自动去重。
 */
@Slf4j
public class DocumentSplitter {

    private final List<SplitStrategy> strategies;
    private final InterfaceDeduplicator deduplicator;

    /** 程序化拆分的默认置信度 */
    private static final double PROGRAMMATIC_CONFIDENCE = 0.95;

    public DocumentSplitter(InterfaceDeduplicator deduplicator) {
        this.deduplicator = deduplicator;
        this.strategies = new ArrayList<>();
        // 按优先级注册策略
        this.strategies.add(new MarkdownHeadingStrategy());
        this.strategies.add(new DelimiterStrategy());
        this.strategies.add(new AnchorStrategy());
        // 按 priority 排序确保顺序
        this.strategies.sort(Comparator.comparingInt(SplitStrategy::priority));
    }

    /**
     * 拆分文档为多个接口片段。
     *
     * @param documentText 原始文档全文
     * @return 拆分后的接口片段列表（已去重），最少返回 1 个（兜底）
     */
    public List<InterfaceSegment> split(String documentText) {
        // 空文档 / 空白文档
        if (documentText == null || documentText.isBlank()) {
            log.debug("[Split] Empty document, returning empty list");
            return List.of();
        }

        String text = documentText.trim();

        // 按策略链依次尝试
        for (SplitStrategy strategy : strategies) {
            try {
                List<InterfaceSegment> result = strategy.trySplit(text);
                if (result != null && result.size() >= 2) {
                    log.info("[Split] Strategy {} → {} segments",
                            strategy.getClass().getSimpleName(), result.size());

                    // 重新编号 + 去重
                    reindex(result);
                    InterfaceDeduplicator.DedupResult deduped = deduplicator.deduplicate(result);

                    if (!deduped.getDeduplications().isEmpty()) {
                        log.info("[Split] Dedup: removed {} duplicates",
                                deduped.getDeduplications().size());
                    }
                    if (!deduped.getWarnings().isEmpty()) {
                        deduped.getWarnings().forEach(w ->
                                log.warn("[Split] Similarity warning: {}", w.getMessage()));
                    }

                    return deduped.getKept();
                }
            } catch (Exception e) {
                log.warn("[Split] Strategy {} failed: {}",
                        strategy.getClass().getSimpleName(), e.getMessage());
            }
        }

        // 所有策略都失败 → 兜底：全文档 = 单接口
        log.info("[Split] All strategies failed, fallback to full-doc single interface");
        InterfaceSegment seg = new InterfaceSegment();
        seg.setInterfaceId("fullDoc_" + Integer.toHexString(text.hashCode()));
        seg.setInterfaceName(extractDocTitle(text));
        seg.setEndpoint("");
        seg.setMethod("");
        seg.setSectionText(text);
        seg.setIndex(0);
        seg.setSplitSource(SplitSource.FULL_DOC);
        seg.setSplitConfidence(PROGRAMMATIC_CONFIDENCE);

        return List.of(seg);
    }

    /**
     * 重新编号，从 0 开始。
     */
    private void reindex(List<InterfaceSegment> segments) {
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).setIndex(i);
        }
    }

    /**
     * 从文档提取标题（取第一行，去掉 # 前缀）。
     */
    private String extractDocTitle(String text) {
        String firstLine = text.split("\\n", 2)[0].trim();
        firstLine = firstLine.replaceAll("^[#\\-\\*\\s]+", "").trim();
        if (firstLine.length() > 60) firstLine = firstLine.substring(0, 60);
        if (firstLine.isBlank()) firstLine = "接口文档";
        return firstLine;
    }
}
