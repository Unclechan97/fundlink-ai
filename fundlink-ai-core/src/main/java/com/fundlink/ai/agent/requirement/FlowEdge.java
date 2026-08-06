package com.fundlink.ai.agent.requirement;

/**
 * 流程边（匹配 FundLink FlowDefinition.graphData.edges 格式）
 */
public class FlowEdge {

    private String id;
    private String source;
    private String target;
    private String label;
    private String conditionExpr;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSource() { return source; }
    public void setSource(String s) { this.source = s; }
    public String getTarget() { return target; }
    public void setTarget(String t) { this.target = t; }
    public String getLabel() { return label; }
    public void setLabel(String l) { this.label = l; }
    public String getConditionExpr() { return conditionExpr; }
    public void setConditionExpr(String e) { this.conditionExpr = e; }
}
