package com.fundlink.ai.agent.testgen;

import java.util.Map;

public class TestCase {
    private String name;
    private String scenarioType;  // NORMAL/BOUNDARY/ERROR
    private Map<String, Object> input;
    private Map<String, Object> expectedOutput;
    private String description;

    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getScenarioType() { return scenarioType; }
    public void setScenarioType(String t) { this.scenarioType = t; }
    public Map<String, Object> getInput() { return input; }
    public void setInput(Map<String, Object> i) { this.input = i; }
    public Map<String, Object> getExpectedOutput() { return expectedOutput; }
    public void setExpectedOutput(Map<String, Object> o) { this.expectedOutput = o; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
}
