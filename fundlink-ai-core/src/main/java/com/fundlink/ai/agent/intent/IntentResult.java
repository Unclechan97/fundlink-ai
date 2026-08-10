package com.fundlink.ai.agent.intent;

import java.util.HashMap;
import java.util.Map;

/**
 * 意图识别结果。
 */
public class IntentResult {

    private IntentType intentType;
    private double confidence;
    private String reason;
    private Map<String, Object> extractedInfo;
    private boolean needUserConfirm;

    // ── Factory methods ──

    public static IntentResult of(IntentType type, double confidence, String reason) {
        IntentResult r = new IntentResult();
        r.intentType = type;
        r.confidence = confidence;
        r.reason = reason;
        r.extractedInfo = new HashMap<>();
        return r;
    }

    public static IntentResult unknown() {
        IntentResult r = new IntentResult();
        r.intentType = IntentType.UNKNOWN;
        r.confidence = 0.0;
        r.reason = "无法识别意图";
        r.extractedInfo = new HashMap<>();
        r.needUserConfirm = true;
        return r;
    }

    // ── Getters / Setters ──

    public IntentType getIntentType() { return intentType; }
    public void setIntentType(IntentType t) { this.intentType = t; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double c) { this.confidence = c; }

    public String getReason() { return reason; }
    public void setReason(String r) { this.reason = r; }

    public Map<String, Object> getExtractedInfo() { return extractedInfo; }
    public void setExtractedInfo(Map<String, Object> m) { this.extractedInfo = m; }

    public boolean isNeedUserConfirm() { return needUserConfirm; }
    public void setNeedUserConfirm(boolean b) { this.needUserConfirm = b; }

    @Override
    public String toString() {
        return "IntentResult{" +
                "intentType=" + intentType +
                ", confidence=" + confidence +
                ", reason='" + reason + '\'' +
                ", needUserConfirm=" + needUserConfirm +
                '}';
    }
}
