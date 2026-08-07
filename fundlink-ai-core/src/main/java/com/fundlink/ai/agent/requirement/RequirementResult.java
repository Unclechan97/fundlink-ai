package com.fundlink.ai.agent.requirement;

import java.util.ArrayList;
import java.util.List;

/**
 * 需求解析结果
 */
public class RequirementResult {

    private ProviderConfig providerConfig;
    private InterfaceSchema interfaceSchema;
    private List<FieldMappingSuggestion> fieldMappings = new ArrayList<>();
    private FlowDsl flowDsl;

    public ProviderConfig getProviderConfig() { return providerConfig; }
    public void setProviderConfig(ProviderConfig p) { this.providerConfig = p; }
    public InterfaceSchema getInterfaceSchema() { return interfaceSchema; }
    public void setInterfaceSchema(InterfaceSchema s) { this.interfaceSchema = s; }

    public List<FieldMappingSuggestion> getFieldMappings() { return fieldMappings; }
    public void setFieldMappings(List<FieldMappingSuggestion> m) { this.fieldMappings = m; }

    public FlowDsl getFlowDsl() { return flowDsl; }
    public void setFlowDsl(FlowDsl f) { this.flowDsl = f; }
}
