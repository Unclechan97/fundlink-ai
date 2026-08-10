package com.fundlink.ai.agent.split;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 接口去重器。
 *
 * 去重规则（优先顺序）：
 * 1. method + endpoint 完全相同 → 保留 sectionText 更长的
 * 2. endpoint 相同，method 不同 → 保留两者（不同接口）
 * 3. endpoint path 相同，host 不同 → 保留两者（不同环境）
 * 4. interfaceName 编辑距离 < 3 → 仅告警，不去重
 */
public class InterfaceDeduplicator {

    /**
     * 去重结果。
     */
    public static class DedupResult {
        private final List<InterfaceSegment> kept;
        private final List<Deduplication> deduplications;
        private final List<SimilarityWarning> warnings;

        public DedupResult(List<InterfaceSegment> kept,
                           List<Deduplication> deduplications,
                           List<SimilarityWarning> warnings) {
            this.kept = kept;
            this.deduplications = deduplications;
            this.warnings = warnings;
        }

        public List<InterfaceSegment> getKept() { return kept; }
        public List<Deduplication> getDeduplications() { return deduplications; }
        public List<SimilarityWarning> getWarnings() { return warnings; }
    }

    public static class Deduplication {
        private final String keptName;
        private final String removedName;
        private final String endpoint;
        private final String reason;

        public Deduplication(String keptName, String removedName, String endpoint, String reason) {
            this.keptName = keptName;
            this.removedName = removedName;
            this.endpoint = endpoint;
            this.reason = reason;
        }

        public String getKeptName() { return keptName; }
        public String getRemovedName() { return removedName; }
        public String getEndpoint() { return endpoint; }
        public String getReason() { return reason; }
    }

    public static class SimilarityWarning {
        private final String message;

        public SimilarityWarning(String message) { this.message = message; }
        public String getMessage() { return message; }
    }

    /**
     * 执行去重，返回保留的 segments 列表。
     */
    public DedupResult deduplicate(List<InterfaceSegment> segments) {
        if (segments == null || segments.size() <= 1) {
            return new DedupResult(
                    segments != null ? segments : List.of(),
                    List.of(), List.of()
            );
        }

        List<Deduplication> deduplications = new ArrayList<>();
        List<SimilarityWarning> warnings = new ArrayList<>();

        // Round 1: 精确去重 — 按 (method, endpoint) 分组
        Map<String, List<InterfaceSegment>> byKey = segments.stream()
                .collect(Collectors.groupingBy(
                        s -> normalizeKey(s.getMethod(), s.getEndpoint()),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        List<InterfaceSegment> kept = new ArrayList<>();
        for (List<InterfaceSegment> group : byKey.values()) {
            if (group.size() == 1) {
                kept.add(group.get(0));
            } else {
                // 保留 sectionText 最长的
                InterfaceSegment best = group.stream()
                        .max(Comparator.comparingInt(s -> s.getSectionText().length()))
                        .orElse(group.get(0));
                kept.add(best);

                for (InterfaceSegment removed : group) {
                    if (removed != best) {
                        deduplications.add(new Deduplication(
                                best.getInterfaceName(),
                                removed.getInterfaceName(),
                                removed.getEndpoint(),
                                "端点重复，保留内容更丰富的版本"
                        ));
                    }
                }
            }
        }

        // Round 2: 名称相似度检测（仅告警）
        for (int i = 0; i < kept.size(); i++) {
            for (int j = i + 1; j < kept.size(); j++) {
                String name1 = kept.get(i).getInterfaceName();
                String name2 = kept.get(j).getInterfaceName();
                if (editDistance(name1, name2) < 3 && name1.length() > 1 && name2.length() > 1) {
                    warnings.add(new SimilarityWarning(
                            "接口 '" + name1 + "' 和 '" + name2 + "' 名称相似，请确认"
                    ));
                }
            }
        }

        return new DedupResult(kept, deduplications, warnings);
    }

    /**
     * 规范化 key: 去掉末尾斜杠和 query string，统一大写。
     */
    private String normalizeKey(String method, String endpoint) {
        if (method == null) method = "";
        if (endpoint == null) endpoint = "";
        return (method + " " + endpoint).trim()
                .replaceAll("/+$", "")
                .replaceAll("\\?.*$", "")
                .toUpperCase();
    }

    /**
     * Levenshtein 编辑距离。
     */
    static int editDistance(String a, String b) {
        if (a == null || b == null) return Integer.MAX_VALUE;
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(dp[i - 1][j - 1],
                            Math.min(dp[i - 1][j], dp[i][j - 1]));
                }
            }
        }
        return dp[a.length()][b.length()];
    }
}
