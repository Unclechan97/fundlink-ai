package com.fundlink.ai.agent.requirement;

import java.util.List;

/**
 * 流程 DSL — AI 生成的流程定义（匹配 FlowDefinition.parse 格式）
 */
public class FlowDsl {

    private List<FlowNode> nodes;
    private List<FlowEdge> edges;

    public List<FlowNode> getNodes() { return nodes; }
    public void setNodes(List<FlowNode> n) { this.nodes = n; }

    public List<FlowEdge> getEdges() { return edges; }
    public void setEdges(List<FlowEdge> e) { this.edges = e; }
}
