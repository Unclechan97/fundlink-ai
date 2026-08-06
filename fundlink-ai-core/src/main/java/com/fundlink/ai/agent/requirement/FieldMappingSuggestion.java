package com.fundlink.ai.agent.requirement;

/**
 * AI 生成的字段映射建议
 */
public class FieldMappingSuggestion {

    /** 资金方字段名 */
    private String fundField;

    /** 内部数据源路径，如 userInfo.realName */
    private String sourcePath;

    /** 转换函数(可选)，如 formatAmount、enumMap */
    private String transform;

    /** 置信度 (0-1) */
    private double confidence;

    public String getFundField() { return fundField; }
    public void setFundField(String f) { this.fundField = f; }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String p) { this.sourcePath = p; }

    public String getTransform() { return transform; }
    public void setTransform(String t) { this.transform = t; }

    public double getConfidence() { return confidence; }
    public void setConfidence(double c) { this.confidence = c; }
}
