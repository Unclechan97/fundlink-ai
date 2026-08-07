package com.fundlink.ai.agent.diagnosis;

@FunctionalInterface
public interface DiagnosisAgent {
    DiagnosisResult diagnose(String instanceNo, String errorDescription);
}
