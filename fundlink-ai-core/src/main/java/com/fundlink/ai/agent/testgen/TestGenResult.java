package com.fundlink.ai.agent.testgen;

import java.util.List;

public class TestGenResult {
    private List<MockRuleSuggestion> mockRules;
    private List<TestCase> testCases;

    public List<MockRuleSuggestion> getMockRules() { return mockRules; }
    public void setMockRules(List<MockRuleSuggestion> r) { this.mockRules = r; }
    public List<TestCase> getTestCases() { return testCases; }
    public void setTestCases(List<TestCase> c) { this.testCases = c; }
}
