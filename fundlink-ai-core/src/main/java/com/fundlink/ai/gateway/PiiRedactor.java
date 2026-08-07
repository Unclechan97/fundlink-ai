package com.fundlink.ai.gateway;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * PII 数据脱敏 — 审计日志写入前自动执行
 * 金融合规要求：存储中不出现明文敏感信息
 */
@Component
public class PiiRedactor {

    // 中国身份证号 (18位)
    private static final Pattern ID_CARD = Pattern.compile("\\b\\d{17}[0-9Xx]\\b");
    // 手机号
    private static final Pattern PHONE = Pattern.compile("\\b1[3-9]\\d{9}\\b");
    // 银行卡号 (16-19位)
    private static final Pattern BANK_CARD = Pattern.compile("\\b\\d{16,19}\\b");

    public String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = ID_CARD.matcher(text).replaceAll(m -> mask(m.group(), 4, 4));
        result = PHONE.matcher(result).replaceAll(m -> maskPhone(m.group()));
        result = BANK_CARD.matcher(result).replaceAll(m -> mask(m.group(), 6, 4));
        return result;
    }

    private String mask(String value, int keepPrefix, int keepSuffix) {
        if (value.length() <= keepPrefix + keepSuffix) return "***";
        return value.substring(0, keepPrefix) + "****" + value.substring(value.length() - keepSuffix);
    }

    private String maskPhone(String phone) {
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
