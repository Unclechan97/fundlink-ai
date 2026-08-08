package com.fundlink.ai.agent.testgen;

import com.fundlink.ai.agent.requirement.FieldMappingSuggestion;
import com.fundlink.ai.agent.requirement.FlowDsl;

import java.util.List;

/**
 * 测试生成 Agent — 基于 flowDsl + fieldMappings 生成 previewData 和按分支的测试用例
 */
public interface TestGenAgent {

    /**
     * @param flowDsl        流程定义（含 CONDITION 节点和出边）
     * @param fieldMappings  字段映射列表
     * @param providerCode   资金方编码
     * @return previewData + testCases（每个 CONDITION 分支一个）
     */
    TestGenResult generate(FlowDsl flowDsl, List<FieldMappingSuggestion> fieldMappings, String providerCode);
}
