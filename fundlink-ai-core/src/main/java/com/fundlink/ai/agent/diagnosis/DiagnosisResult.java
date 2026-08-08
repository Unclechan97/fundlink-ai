package com.fundlink.ai.agent.diagnosis;

import java.util.List;
import java.util.Map;

/**
 * 诊断结果 — 含修正建议，可注入回 RequirementAgent.previousErrors
 */
public class DiagnosisResult {

    private String rootCause;
    private List<String> causeChain;
    private String fixSuggestion;
    private double confidence;

    /** 修正后的配置 (EDIT_AND_RETRY 场景) */
    private Map<String, Object> correctedConfig;

    /** 失败阶段 */
    private String phase;

    public String getRootCause() { return rootCause; }
    public void setRootCause(String r) { this.rootCause = r; }

    public List<String> getCauseChain() { return causeChain; }
    public void setCauseChain(List<String> c) { this.causeChain = c; }

    public String getFixSuggestion() { return fixSuggestion; }
    public void setFixSuggestion(String s) { this.fixSuggestion = s; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double c) { this.confidence = c; }

    public Map<String, Object> getCorrectedConfig() { return correctedConfig; }
    public void setCorrectedConfig(Map<String, Object> c) { this.correctedConfig = c; }

    public String getPhase() { return phase; }
    public void setPhase(String p) { this.phase = p; }
}
