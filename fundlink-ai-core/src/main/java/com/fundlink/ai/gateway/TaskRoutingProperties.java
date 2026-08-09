package com.fundlink.ai.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * YML 配置映射: fundlink.llm.router.task-routing
 */
@Component
@ConfigurationProperties(prefix = "fundlink.llm.router")
public class TaskRoutingProperties {

    private final Map<String, ProviderModel> taskRouting = new HashMap<>();

    public Map<String, ProviderModel> getTaskRouting() {
        return taskRouting;
    }
}
