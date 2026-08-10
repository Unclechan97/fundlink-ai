package com.fundlink.ai.agent.split;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy 2: 分隔线匹配。
 * 匹配 ---、***、=== 分隔线，以及中文数字序号（一、二、三…）作为分界点。
 */
public class DelimiterStrategy implements SplitStrategy {

    /** 匹配分隔线: ---, ***, === (至少 3 个字符的行) */
    private static final Pattern HORIZONTAL_RULE = Pattern.compile(
            "\n(---+|\\*{3,}|===+)\n"
    );

    /** 匹配中文数字序号开头: 一、二、三… 或 1. 2. 3. */
    private static final Pattern CHINESE_NUMBERED = Pattern.compile(
            "\n(?=[\\u4e00-\\u9fa5]{1,2}[、．.])"
    );

    /** 从内容中提取 HTTP 方法和路径 */
    private static final Pattern METHOD_ENDPOINT = Pattern.compile(
            "(POST|GET|PUT|DELETE|PATCH)\\s+(/[a-zA-Z0-9_\\-/{}.?=&%]+)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public int priority() {
        return 2;
    }

    @Override
    public List<InterfaceSegment> trySplit(String documentText) {
        // 先尝试 --- 等分隔线
        List<Integer> splitPoints = findHorizontalRuleSplits(documentText);

        // 如果没找到分隔线，尝试中文序号
        if (splitPoints.size() < 2) {
            splitPoints = findChineseNumberedSplits(documentText);
        }

        if (splitPoints.size() < 2) {
            return List.of();
        }

        return buildSegments(documentText, splitPoints);
    }

    private List<Integer> findHorizontalRuleSplits(String documentText) {
        List<Integer> points = new ArrayList<>();
        // 文档开头作为第一个分界点
        points.add(0);

        Matcher m = HORIZONTAL_RULE.matcher(documentText);
        while (m.find()) {
            // 分隔线结束位置作为下一个 section 的开始
            points.add(m.end());
        }

        return points;
    }

    private List<Integer> findChineseNumberedSplits(String documentText) {
        List<Integer> points = new ArrayList<>();
        points.add(0);

        Matcher m = CHINESE_NUMBERED.matcher(documentText);
        while (m.find()) {
            points.add(m.start() + 1); // +1 跳过 \n
        }

        return points;
    }

    private List<InterfaceSegment> buildSegments(String documentText, List<Integer> splitPoints) {
        List<InterfaceSegment> segments = new ArrayList<>();

        for (int i = 0; i < splitPoints.size(); i++) {
            int start = splitPoints.get(i);
            int end = (i + 1 < splitPoints.size()) ? splitPoints.get(i + 1) : documentText.length();

            String sectionText = documentText.substring(start, end).trim();
            if (sectionText.isBlank()) continue;

            String name = extractName(sectionText);
            EndpointInfo ep = extractEndpoint(sectionText);

            String interfaceId = MarkdownHeadingStrategy.generateId(name, ep.endpoint, i);
            InterfaceSegment seg = new InterfaceSegment();
            seg.setInterfaceId(interfaceId);
            seg.setInterfaceName(name);
            seg.setEndpoint(ep.endpoint);
            seg.setMethod(ep.method);
            seg.setSectionText(sectionText);
            seg.setIndex(i);
            seg.setSplitSource(SplitSource.DELIMITER);
            seg.setSplitConfidence(0.90);
            segments.add(seg);
        }

        return segments;
    }

    private String extractName(String sectionText) {
        // 取第一行作为名称
        String firstLine = sectionText.split("\\n", 2)[0].trim();
        // 清理前缀符号
        firstLine = firstLine.replaceAll("^[#\\-\\*\\s]+", "").trim();
        if (firstLine.length() > 50) firstLine = firstLine.substring(0, 50);
        if (firstLine.isBlank()) firstLine = "接口" + (sectionText.hashCode() & 0xFFFF);
        return firstLine;
    }

    private EndpointInfo extractEndpoint(String sectionText) {
        Matcher m = METHOD_ENDPOINT.matcher(sectionText);
        if (m.find()) {
            return new EndpointInfo(m.group(1).toUpperCase(), m.group(2));
        }
        return new EndpointInfo("", "");
    }

    private record EndpointInfo(String method, String endpoint) {}
}
