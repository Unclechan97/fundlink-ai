package com.fundlink.ai.agent.testgen;

@FunctionalInterface
public interface TestGenAgent {
    TestGenResult generate(String interfaceDoc, String providerCode, int scenarioCount);
}
