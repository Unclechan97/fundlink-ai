package com.fundlink.ai.agent.loop;

import com.fundlink.ai.agent.requirement.FieldMappingSuggestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证嵌套字段的渲染输出检查逻辑 — 叶子名匹配。
 */
class TemplateValidatorTest {

    @Test
    void nestedFieldCheckByLeafName() {
        String renderResult = """
            {
              "loanNo": "LN001",
              "repayAccount": {
                "bankCode": "308584000013",
                "bankName": "中国工商银行"
              },
              "feeDetail": {
                "principal": "",
                "repayPeriods": []
              },
              "attach": ""
            }""";

        List<String> missing = checkMissing(renderResult,
                fm("loanNo"),
                fm("repayAccount.bankCode"),
                fm("repayAccount.bankName"),
                fm("feeDetail.principal"),
                fm("feeDetail.repayPeriods"),
                fm("attach"));

        assertThat(missing).as("Nested fields should match by leaf name").isEmpty();
    }

    @Test
    void arrayLeafFieldCheck() {
        String renderResult = """
            {"repayPeriods":[{"periodNo":"1","periodStart":"2024-01-01","periodEnd":"2024-01-31"}]}""";

        List<String> missing = checkMissing(renderResult,
                fm("repayPeriods[].periodNo"),
                fm("repayPeriods[].periodStart"),
                fm("repayPeriods[].periodEnd"));

        assertThat(missing).as("Array item fields should match by leaf name").isEmpty();
    }

    @Test
    void emptySourcePathFieldsShouldBeSkipped() {
        // 当 sourcePath 为空，跳过检查 —— 空数组中不会展开子字段
        String renderResult = """
            {"loanNo":"LN001","repayPeriods":[],"attach":""}""";

        FieldMappingSuggestion emptySp = fm("repayPeriods[].periodNo");
        emptySp.setSourcePath(null);       // 空 → 跳过
        FieldMappingSuggestion hasSp = fm("loanNo");
        hasSp.setSourcePath("loanInfo.loanNo"); // 有值 → 检查

        List<String> missing = checkMissing2(renderResult, emptySp, hasSp);
        assertThat(missing).as("Empty sourcePath should be skipped, loanNo should be found")
                .isEmpty();
    }

    @Test
    void emptySourcePathArrayFieldNotInRender() {
        // 空 sourcePath 的数组字段即使渲染结果里没有，也不应报缺失
        String renderResult = """
            {"repayPeriods":[]}""";

        FieldMappingSuggestion emptySp = fm("repayPeriods[].periodNo");
        emptySp.setSourcePath("");

        List<String> missing = checkMissing2(renderResult, emptySp);
        assertThat(missing).isEmpty();
    }

    // -- helpers --

    private List<String> checkMissing(String renderResult, FieldMappingSuggestion... mappings) {
        List<String> missing = new ArrayList<>();
        for (FieldMappingSuggestion m : mappings) {
            String fundField = m.getFundField();
            if (fundField == null) continue;
            String leafName = fundField.contains(".")
                    ? fundField.substring(fundField.lastIndexOf('.') + 1)
                    : fundField;
            leafName = leafName.replace("[]", "");
            if (!renderResult.contains("\"" + leafName + "\"")) {
                missing.add(fundField + " (leaf=" + leafName + ")");
            }
        }
        return missing;
    }

    /** 带 sourcePath 的字段检查 */
    private List<String> checkMissing2(String renderResult, FieldMappingSuggestion... mappings) {
        List<String> missing = new ArrayList<>();
        for (FieldMappingSuggestion m : mappings) {
            String fundField = m.getFundField();
            if (fundField == null) continue;
            if (m.getSourcePath() == null || m.getSourcePath().isBlank()) continue;
            String leafName = fundField.contains(".")
                    ? fundField.substring(fundField.lastIndexOf('.') + 1)
                    : fundField;
            leafName = leafName.replace("[]", "");
            if (!renderResult.contains("\"" + leafName + "\"")) {
                missing.add(fundField);
            }
        }
        return missing;
    }

    private static FieldMappingSuggestion fm(String fundField) {
        FieldMappingSuggestion m = new FieldMappingSuggestion();
        m.setFundField(fundField);
        m.setSourcePath("test");
        return m;
    }
}
