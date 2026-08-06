package com.fundlink.ai.agent.requirement;

import java.util.List;

/**
 * 接口 Schema — 从文档中提取的结构化接口定义
 */
public class InterfaceSchema {

    private String endpoint;
    private String method;
    private List<InterfaceField> fields;

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String e) { this.endpoint = e; }

    public String getMethod() { return method; }
    public void setMethod(String m) { this.method = m; }

    public List<InterfaceField> getFields() { return fields; }
    public void setFields(List<InterfaceField> f) { this.fields = f; }
}
