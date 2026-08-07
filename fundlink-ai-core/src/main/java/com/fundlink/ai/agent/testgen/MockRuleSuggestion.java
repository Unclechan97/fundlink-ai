package com.fundlink.ai.agent.testgen;

public class MockRuleSuggestion {
    private String ruleName;
    private String sourceCode;
    private String matchExpr;
    private String responseJson;
    private int delayMs;

    public String getRuleName() { return ruleName; }
    public void setRuleName(String n) { this.ruleName = n; }
    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String s) { this.sourceCode = s; }
    public String getMatchExpr() { return matchExpr; }
    public void setMatchExpr(String e) { this.matchExpr = e; }
    public String getResponseJson() { return responseJson; }
    public void setResponseJson(String j) { this.responseJson = j; }
    public int getDelayMs() { return delayMs; }
    public void setDelayMs(int d) { this.delayMs = d; }
}
