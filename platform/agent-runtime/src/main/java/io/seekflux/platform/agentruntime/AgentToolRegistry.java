package io.seekflux.platform.agentruntime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(Collection<AgentTool> tools) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            AgentTool previous = indexed.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate agent tool: " + tool.name());
            }
        }
        this.tools = Map.copyOf(indexed);
    }

    public AgentTool require(String name) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("unknown agent tool: " + name);
        }
        return tool;
    }

    public Map<String, String> versionsFor(Collection<String> names) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (String name : names) {
            AgentTool tool = require(name);
            versions.put(name, tool.schema().version());
        }
        return Map.copyOf(versions);
    }
}
