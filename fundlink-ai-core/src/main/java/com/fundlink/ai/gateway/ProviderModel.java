package com.fundlink.ai.gateway;

/**
 * YML 中 task-routing 的单个条目: { provider, model }
 */
public class ProviderModel {

    private String provider;
    private String model;

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
