package com.fundlink.ai.agent.split;

import java.util.regex.Pattern;

/**
 * 从 HTTP endpoint 派生可读的短名称，用作 interfaceId。
 *
 * <pre>
 *   POST /api/loan/apply  →  LOAN_APPLY
 *   POST /api/loan/query  →  LOAN_QUERY
 *   POST /api/repay/apply →  REPAY_APPLY
 *   POST /api/repay/query →  REPAY_QUERY
 *   GET  /api/user/info  →  USER_INFO
 * </pre>
 *
 * 无法从 endpoint 派生时回退为 name 的 sanitize 版本。
 */
public final class EndpointShortName {

    private static final Pattern NON_ALPHANUM = Pattern.compile("[^A-Z0-9_]");
    private static final Pattern UNDERSCORE_COLLAPSE = Pattern.compile("_+");

    private EndpointShortName() {}

    /**
     * 从 endpoint 派生短标识符。
     *
     * @param endpoint HTTP 路径，如 "/api/loan/apply"
     * @param fallbackName 无法解析时的回退名称
     * @return 如 "LOAN_APPLY"
     */
    public static String fromEndpoint(String endpoint, String fallbackName) {
        if (endpoint == null || endpoint.isBlank()) {
            return sanitize(fallbackName);
        }

        String path = endpoint.trim();

        // 去掉 query string
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        // 去掉末尾斜杠
        path = path.replaceAll("/+$", "");
        if (path.isEmpty()) return sanitize(fallbackName);

        // 按 "/" 拆分，取最后 1~3 个有意义的段
        String[] parts = path.split("/");
        java.util.List<String> meaningful = new java.util.ArrayList<>();
        for (int i = parts.length - 1; i >= 0 && meaningful.size() < 3; i--) {
            String p = parts[i].trim().toUpperCase();
            // 跳过空段、纯数字、常见前缀
            if (p.isEmpty() || p.matches("\\d+")) continue;
            if ("API".equals(p) || "V1".equals(p) || "V2".equals(p) || "V3".equals(p)) continue;
            meaningful.add(0, p);
        }

        if (meaningful.isEmpty()) return sanitize(fallbackName);

        String result = String.join("_", meaningful);

        // 清理非字母数字下划线
        result = NON_ALPHANUM.matcher(result).replaceAll("");
        result = UNDERSCORE_COLLAPSE.matcher(result).replaceAll("_");
        result = result.replaceAll("^_|_$", "");

        if (result.isEmpty()) return sanitize(fallbackName);

        // 限制长度
        if (result.length() > 50) result = result.substring(0, 50);

        return result;
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) return "UNKNOWN";
        String s = name.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "");
        if (s.length() > 20) s = s.substring(0, 20);
        if (s.isBlank()) return "UNKNOWN";
        return s.toUpperCase();
    }
}
