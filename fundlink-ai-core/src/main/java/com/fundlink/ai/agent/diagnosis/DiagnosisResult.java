package com.fundlink.ai.agent.diagnosis;

import java.util.List;

public class DiagnosisResult {
    private String rootCause;
    private List<String> causeChain;
    private String fixSuggestion;
    private double confidence;

    public String getRootCause() { return rootCause; }
    public void setRootCause(String r) { this.rootCause = r; }
    public List<String> getCauseChain() { return causeChain; }
    public void setCauseChain(List<String> c) { this.causeChain = c; }
    public String getFixSuggestion() { return fixSuggestion; }
    public void setFixSuggestion(String s) { this.fixSuggestion = s; }
    public double getConfidence() { return confidence; }
    public void setConfidence(double c) { this.confidence = c; }
}
