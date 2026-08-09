package com.fundlink.ai.agent;

import java.util.Locale;
import java.util.Set;

/**
 * 接口文档类型自动检测器 — 基于关键词计分。
 * <p>
 * 用于在 LLM 解析前确定 flowType（驱动 RAG 查询和字段目录选择）。
 * LLM 解析阶段会输出 flow_type，以 LLM 判决为准。
 * <p>
 * 远期扩展：单文档含多接口时，各分块独立调用本检测器。
 */
public final class FlowTypeDetector {

    private static final Set<String> LOAN_KEYWORDS =
            Set.of("loan", "lend", "disburse", "放款", "借款", "贷款申请", "提款", "支用");

    private static final Set<String> CREDIT_KEYWORDS =
            Set.of("credit", "limit", "授信", "额度", "征信");

    private static final Set<String> REPAY_KEYWORDS =
            // 注意: "还款方式" / "repayMethod" 是字段名，不能算 REPAY 信号
            Set.of("repay", "repayment", "还款申请", "主动还款", "提前还款", "代扣", "扣款");

    private FlowTypeDetector() {}

    /**
     * 检测接口文档类型。
     *
     * @param documentText      接口文档全文
     * @param providedFlowType  用户选定的类型（可为空，非空时优先返回）
     * @return LOAN / CREDIT / REPAY
     */
    public static String detect(String documentText, String providedFlowType) {
        // 用户选定 → 直接返回
        if (providedFlowType != null && !providedFlowType.isBlank()) {
            String upper = providedFlowType.toUpperCase(Locale.ROOT).trim();
            if (Set.of("LOAN", "CREDIT", "REPAY").contains(upper)) {
                return upper;
            }
        }

        // 关键词计分
        if (documentText == null || documentText.isBlank()) {
            return "LOAN";
        }

        String lower = documentText.toLowerCase(Locale.ROOT);

        int loanScore = countMatches(lower, LOAN_KEYWORDS);
        int creditScore = countMatches(lower, CREDIT_KEYWORDS);
        int repayScore = countMatches(lower, REPAY_KEYWORDS);

        if (repayScore > loanScore && repayScore > creditScore) {
            return "REPAY";
        }
        if (creditScore > loanScore && creditScore > repayScore) {
            return "CREDIT";
        }
        return "LOAN";
    }

    private static int countMatches(String text, Set<String> keywords) {
        int count = 0;
        for (String kw : keywords) {
            int idx = 0;
            while ((idx = text.indexOf(kw, idx)) != -1) {
                count++;
                idx += kw.length();
            }
        }
        return count;
    }
}
