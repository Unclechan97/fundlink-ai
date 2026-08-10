package com.fundlink.ai.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tool 注册表。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        tools.put(tool.getDefinition().getName(), tool);
    }

    public Tool find(String name) {
        return tools.get(name);
    }

    public List<Map<String, Object>> toOpenAiTools() {
        return tools.values().stream()
                .map(t -> t.getDefinition().toOpenAiFormat())
                .toList();
    }

    public int size() {
        return tools.size();
    }
}
