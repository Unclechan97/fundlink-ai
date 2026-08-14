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
    private String parseError;
    /** LLM 解析阶段自动识别的流程类型: LOAN / CREDIT / REPAY */
    private String flowType;

    // ── Phase 3: 多接口支持 ──

    /** 接口唯一标识 */
    private String interfaceId;
    /** 接口名称 */
    private String interfaceName;
    /** 在文档中的序号 (0-based) */
    private int interfaceIndex;
    /** 本文档拆出的接口总数 */
    private int totalInterfaces;

    /** 用户可见提示（如"知识库暂不可用，解析未参考历史案例"），正常为 null */
    private String notice;

    public ProviderConfig getProviderConfig() { return providerConfig; }
    public void setProviderConfig(ProviderConfig p) { this.providerConfig = p; }
    public InterfaceSchema getInterfaceSchema() { return interfaceSchema; }
    public void setInterfaceSchema(InterfaceSchema s) { this.interfaceSchema = s; }

    public List<FieldMappingSuggestion> getFieldMappings() { return fieldMappings; }
    public void setFieldMappings(List<FieldMappingSuggestion> m) { this.fieldMappings = m; }

    public FlowDsl getFlowDsl() { return flowDsl; }
    public void setFlowDsl(FlowDsl f) { this.flowDsl = f; }

    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }

    public String getFlowType() { return flowType; }
    public void setFlowType(String flowType) { this.flowType = flowType; }

    public String getInterfaceId() { return interfaceId; }
    public void setInterfaceId(String id) { this.interfaceId = id; }

    public String getInterfaceName() { return interfaceName; }
    public void setInterfaceName(String name) { this.interfaceName = name; }

    public int getInterfaceIndex() { return interfaceIndex; }
    public void setInterfaceIndex(int i) { this.interfaceIndex = i; }

    public int getTotalInterfaces() { return totalInterfaces; }
    public void setTotalInterfaces(int n) { this.totalInterfaces = n; }

    public String getNotice() { return notice; }
    public void setNotice(String notice) { this.notice = notice; }
}
