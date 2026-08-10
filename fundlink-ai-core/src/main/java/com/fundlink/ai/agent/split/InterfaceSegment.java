package com.fundlink.ai.agent.split;

/**
 * 接口片段 — 拆分后每个独立接口的完整信息。
 */
public class InterfaceSegment {

    /** 唯一标识，如 "loanApply_a1b2c3"（名称 + 短哈希） */
    private String interfaceId;
    /** 接口名称，如 "放款申请" */
    private String interfaceName;
    /** 完整端点，如 "POST /api/loan/apply" */
    private String endpoint;
    /** HTTP 方法，如 POST */
    private String method;
    /** 该接口的文档原文片段 */
    private String sectionText;
    /** 流程类型初步判定：LOAN / CREDIT / REPAY */
    private String flowType;
    /** 在文档中的序号 (0-based) */
    private int index;
    /** 隶属的顶级标题 */
    private String parentHeading;
    /** 使用了哪种拆分策略 */
    private SplitSource splitSource;
    /** 拆分置信度：程序化 = 0.95，LLM 校验后可能调整 */
    private double splitConfidence;

    // ── Constructors ──

    public InterfaceSegment() {}

    public InterfaceSegment(String interfaceId, String interfaceName, String endpoint,
                            String method, String sectionText, int index,
                            SplitSource splitSource, double splitConfidence) {
        this.interfaceId = interfaceId;
        this.interfaceName = interfaceName;
        this.endpoint = endpoint;
        this.method = method;
        this.sectionText = sectionText;
        this.index = index;
        this.splitSource = splitSource;
        this.splitConfidence = splitConfidence;
    }

    // ── Getters / Setters ──

    public String getInterfaceId() { return interfaceId; }
    public void setInterfaceId(String interfaceId) { this.interfaceId = interfaceId; }

    public String getInterfaceName() { return interfaceName; }
    public void setInterfaceName(String interfaceName) { this.interfaceName = interfaceName; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getSectionText() { return sectionText; }
    public void setSectionText(String sectionText) { this.sectionText = sectionText; }

    public String getFlowType() { return flowType; }
    public void setFlowType(String flowType) { this.flowType = flowType; }

    public int getIndex() { return index; }
    public void setIndex(int index) { this.index = index; }

    public String getParentHeading() { return parentHeading; }
    public void setParentHeading(String parentHeading) { this.parentHeading = parentHeading; }

    public SplitSource getSplitSource() { return splitSource; }
    public void setSplitSource(SplitSource splitSource) { this.splitSource = splitSource; }

    public double getSplitConfidence() { return splitConfidence; }
    public void setSplitConfidence(double splitConfidence) { this.splitConfidence = splitConfidence; }

    @Override
    public String toString() {
        return "InterfaceSegment{" +
                "interfaceId='" + interfaceId + '\'' +
                ", interfaceName='" + interfaceName + '\'' +
                ", endpoint='" + endpoint + '\'' +
                ", splitSource=" + splitSource +
                '}';
    }
}
