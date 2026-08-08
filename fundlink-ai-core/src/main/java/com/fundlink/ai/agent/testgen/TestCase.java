package com.fundlink.ai.agent.testgen;

import java.util.List;
import java.util.Map;

/**
 * 测试用例 — 对应一个 CONDITION 分支
 */
public class TestCase {

    /** 用例名称 */
    private String name;

    /** 目标 CONDITION 出边 ID (如 "ec1") */
    private String targetBranch;

    /** 场景类型: NORMAL / BOUNDARY / ERROR */
    private String scenarioType;

    /** FlowEngine.executeSync 输入数据 */
    private Map<String, Object> inputData;

    /** 干跑后期望在 FlowResult.data 中存在的 key */
    private Map<String, Object> expectedContextKeys;

    /** 本条用例需要的 Mock 规则 */
    private List<MockRuleSuggestion> mockRules;

    /** 用例描述 */
    private String description;

    // -- getters / setters --

    public String getName() { return name; }
    public void setName(String n) { this.name = n; }

    public String getTargetBranch() { return targetBranch; }
    public void setTargetBranch(String t) { this.targetBranch = t; }

    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String t) { this.scenarioType = t; }

    public Map<String, Object> getInputData() { return inputData; }
    public void setInputData(Map<String, Object> i) { this.inputData = i; }

    public Map<String, Object> getExpectedContextKeys() { return expectedContextKeys; }
    public void setExpectedContextKeys(Map<String, Object> e) { this.expectedContextKeys = e; }

    public List<MockRuleSuggestion> getMockRules() { return mockRules; }
    public void setMockRules(List<MockRuleSuggestion> m) { this.mockRules = m; }

    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
}
