package com.fundlink.ai.agent.requirement;

/**
 * 接口字段定义
 */
public class InterfaceField {

    private String name;
    private String type;
    private boolean required;
    private String description;

    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getType() { return type; }
    public void setType(String t) { this.type = t; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean r) { this.required = r; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
}
