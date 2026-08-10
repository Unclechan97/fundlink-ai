package com.fundlink.ai.agent.split;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy 1: Markdown 标题匹配。
 * 匹配 ## 或 ### 标题中包含接口关键词的标题行，以标题为分界点切分文档。
 */
public class MarkdownHeadingStrategy implements SplitStrategy {

    /** 匹配 ## / ### 级别，标题包含接口相关关键词 */
    static final Pattern INTERFACE_HEADING = Pattern.compile(
            "^#{2,3}\\s*(.+?(?:接口|申请|查询|通知|回调|确认|取消|退款|更新|修改|删除|创建|新增" +
            "|API|api|endpoint|Loan|loan|Apply|Query|Callback).*)",
            Pattern.MULTILINE
    );

    /** 从标题中提取 HTTP 方法和路径 */
    private static final Pattern METHOD_ENDPOINT_IN_TEXT = Pattern.compile(
            "(POST|GET|PUT|DELETE|PATCH)\\s+(/[a-zA-Z0-9_\\-/{}.?=&%]+)",
            Pattern.CASE_INSENSITIVE
    );

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public List<InterfaceSegment> trySplit(String documentText) {
        Matcher matcher = INTERFACE_HEADING.matcher(documentText);

        // 收集所有标题匹配位置
        List<HeadingMatch> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(new HeadingMatch(matcher.start(), matcher.end(), matcher.group(1).trim()));
        }

        if (matches.size() < 2) {
            return List.of(); // 需要至少 2 个标题才是有意义的拆分
        }

        List<InterfaceSegment> segments = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            HeadingMatch current = matches.get(i);
            int contentStart = current.endIndex;
            int contentEnd = (i + 1 < matches.size()) ? matches.get(i + 1).startIndex : documentText.length();

            String sectionText = documentText.substring(current.startIndex, contentEnd).trim();
            String heading = current.headingText;

            // 尝试从标题或内容中提取 endpoint
            EndpointInfo ep = extractEndpoint(heading, sectionText);

            segments.add(buildSegment(heading, ep, sectionText, i, matches.size(), SplitSource.MARKDOWN_HEADING));
        }

        return segments;
    }

    private EndpointInfo extractEndpoint(String heading, String sectionText) {
        // 先从标题提取
        Matcher m = METHOD_ENDPOINT_IN_TEXT.matcher(heading);
        if (m.find()) {
            return new EndpointInfo(m.group(1).toUpperCase(), m.group(2));
        }
        // 再从内容前 500 字符提取
        String preview = sectionText.length() > 500 ? sectionText.substring(0, 500) : sectionText;
        m = METHOD_ENDPOINT_IN_TEXT.matcher(preview);
        if (m.find()) {
            return new EndpointInfo(m.group(1).toUpperCase(), m.group(2));
        }
        return new EndpointInfo("", "");
    }

    private InterfaceSegment buildSegment(String interfaceName, EndpointInfo ep,
                                          String sectionText, int index, int total,
                                          SplitSource source) {
        String interfaceId = generateId(interfaceName, ep.endpoint, index);
        InterfaceSegment seg = new InterfaceSegment();
        seg.setInterfaceId(interfaceId);
        seg.setInterfaceName(interfaceName);
        seg.setEndpoint(ep.endpoint);
        seg.setMethod(ep.method);
        seg.setSectionText(sectionText);
        seg.setIndex(index);
        seg.setSplitSource(source);
        seg.setSplitConfidence(0.95);
        return seg;
    }

    static String generateId(String name, String endpoint, int index) {
        String shortName = EndpointShortName.fromEndpoint(endpoint, name);
        if (!"UNKNOWN".equals(shortName)) return shortName;
        // 回退：sanitize name
        String base = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "");
        if (base.length() > 20) base = base.substring(0, 20);
        if (!base.isBlank()) return base.toUpperCase();
        return "INTERFACE_" + index;
    }

    // ── 内部类 ──

    private record HeadingMatch(int startIndex, int endIndex, String headingText) {}

    record EndpointInfo(String method, String endpoint) {}
}
