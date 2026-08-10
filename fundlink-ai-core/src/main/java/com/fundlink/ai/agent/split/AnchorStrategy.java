package com.fundlink.ai.agent.split;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy 3: 端点锚点匹配。
 * 以接口 URL 定义语句为锚点进行切分，两个锚点之间的内容属于前一个接口。
 */
public class AnchorStrategy implements SplitStrategy {

    /** 匹配接口端点定义语句: "接口地址: POST /api/loan/apply" 等形式 */
    static final Pattern ENDPOINT_PATTERN = Pattern.compile(
            "(?:接口(?:名称|地址|路径|URL)|请求地址|endpoint|API|url)\\s*[：:]\\s*" +
            "(?:POST|GET|PUT|DELETE|PATCH)?\\s*(/[a-zA-Z0-9_\\-/{}.?=&%]+)",
            Pattern.CASE_INSENSITIVE
    );

    /** 从端点行提取 HTTP 方法 + 路径 */
    private static final Pattern FULL_ENDPOINT = Pattern.compile(
            "(POST|GET|PUT|DELETE|PATCH)\\s+(/[a-zA-Z0-9_\\-/{}.?=&%]+)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public int priority() {
        return 3;
    }

    @Override
    public List<InterfaceSegment> trySplit(String documentText) {
        Matcher matcher = ENDPOINT_PATTERN.matcher(documentText);

        List<Integer> anchorPositions = new ArrayList<>();
        List<String> anchorLines = new ArrayList<>();
        while (matcher.find()) {
            // 找到这行的起始位置
            int lineStart = findLineStart(documentText, matcher.start());
            anchorPositions.add(lineStart);
            anchorLines.add(matcher.group().trim());
        }

        if (anchorPositions.size() < 2) {
            return List.of(); // 少于 2 个锚点，无需拆分
        }

        List<InterfaceSegment> segments = new ArrayList<>();
        for (int i = 0; i < anchorPositions.size(); i++) {
            int start = anchorPositions.get(i);
            int end = (i + 1 < anchorPositions.size()) ? anchorPositions.get(i + 1) : documentText.length();

            String sectionText = documentText.substring(start, end).trim();
            String anchorLine = anchorLines.get(i);

            EndpointInfo ep = parseEndpoint(anchorLine, sectionText);
            String name = extractName(anchorLine, sectionText);

            String interfaceId = MarkdownHeadingStrategy.generateId(name, ep.endpoint, i);
            InterfaceSegment seg = new InterfaceSegment();
            seg.setInterfaceId(interfaceId);
            seg.setInterfaceName(name);
            seg.setEndpoint(ep.endpoint);
            seg.setMethod(ep.method);
            seg.setSectionText(sectionText);
            seg.setIndex(i);
            seg.setSplitSource(SplitSource.ANCHOR);
            seg.setSplitConfidence(0.92);
            segments.add(seg);
        }

        return segments;
    }

    private int findLineStart(String text, int pos) {
        int lineStart = text.lastIndexOf('\n', pos);
        return lineStart >= 0 ? lineStart + 1 : 0;
    }

    private EndpointInfo parseEndpoint(String anchorLine, String sectionText) {
        // 从锚点行提取
        Matcher m = FULL_ENDPOINT.matcher(anchorLine);
        if (m.find()) {
            return new EndpointInfo(m.group(1).toUpperCase(), m.group(2));
        }
        // 从锚点行提取仅路径
        m = Pattern.compile("(/[a-zA-Z0-9_\\-/{}.?=&%]+)").matcher(anchorLine);
        if (m.find()) {
            // 尝试从上下文找 method
            Matcher methodM = Pattern.compile("(POST|GET|PUT|DELETE|PATCH)",
                    Pattern.CASE_INSENSITIVE).matcher(anchorLine);
            String method = methodM.find() ? methodM.group(1).toUpperCase() : "POST";
            return new EndpointInfo(method, m.group(1));
        }
        return new EndpointInfo("", "");
    }

    private String extractName(String anchorLine, String sectionText) {
        // 从锚点行提取名称
        String name = anchorLine.replaceAll("接口(?:名称|地址|路径|URL)|请求地址|endpoint|API|url", "")
                .replaceAll("[：:].*$", "").trim();
        if (!name.isBlank() && name.length() < 40) return name;

        // 取 section 的第一行作为名称
        String firstLine = sectionText.split("\\n", 2)[0].trim();
        firstLine = firstLine.replaceAll("^[#\\-\\*\\s]+", "").trim();
        if (firstLine.length() > 50) firstLine = firstLine.substring(0, 50);
        if (!firstLine.isBlank()) return firstLine;

        return "接口" + (sectionText.hashCode() & 0xFFFF);
    }

    private record EndpointInfo(String method, String endpoint) {}
}
