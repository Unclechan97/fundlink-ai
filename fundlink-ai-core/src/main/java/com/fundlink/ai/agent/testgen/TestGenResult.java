package com.fundlink.ai.agent.testgen;

import java.util.List;
import java.util.Map;

/**
 * 测试生成结果
 */
public class TestGenResult {

    /** 模板预览数据 — 给 VALIDATE 阶段渲染用 */
    private Map<String, Object> previewData;

    /** Mock 规则 (全局级别，可为空；具体 mock 在 TestCase 中) */
    private List<MockRuleSuggestion> mockRules;

    /** 测试用例 — 每个 CONDITION 分支至少一个 */
    private List<TestCase> testCases;

    /** LLM 解析失败时非空 */
    private String parseError;

    public Map<String, Object> getPreviewData() { return previewData; }
    public void setPreviewData(Map<String, Object> p) { this.previewData = p; }

    public List<MockRuleSuggestion> getMockRules() { return mockRules; }
    public void setMockRules(List<MockRuleSuggestion> r) { this.mockRules = r; }

    public List<TestCase> getTestCases() { return testCases; }
    public void setTestCases(List<TestCase> c) { this.testCases = c; }

    public String getParseError() { return parseError; }
    public void setParseError(String e) { this.parseError = e; }
}
