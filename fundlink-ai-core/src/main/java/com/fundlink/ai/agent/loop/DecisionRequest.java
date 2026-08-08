package com.fundlink.ai.agent.loop;

import com.fundlink.ai.agent.requirement.RequirementResult;

/**
 * 人工决策请求 (设计 §3.5)
 */
public class DecisionRequest {

    private Long taskId;
    private String decision;   // RETRY | SKIP | EDIT_AND_RETRY | ABORT | PUBLISH
    private RequirementResult editedResult;
    private String comment;

    public Long getTaskId() { return taskId; }
    public void setTaskId(Long t) { this.taskId = t; }

    public String getDecision() { return decision; }
    public void setDecision(String d) { this.decision = d; }

    public RequirementResult getEditedResult() { return editedResult; }
    public void setEditedResult(RequirementResult r) { this.editedResult = r; }

    public String getComment() { return comment; }
    public void setComment(String c) { this.comment = c; }
}
