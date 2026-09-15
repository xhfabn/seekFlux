package io.seekflux.platform.agentruntime.domain.service.tool;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentToolRegistry {

    private final Map<String, AgentTool> tools;

    public AgentToolRegistry(Collection<AgentTool> tools) {
        this(tools, AgentToolRegistrationPolicy.SAFE_ONLY);
    }

    public AgentToolRegistry(
            Collection<AgentTool> tools,
            AgentToolRegistrationPolicy registrationPolicy) {
        Map<String, AgentTool> indexed = new LinkedHashMap<>();
        for (AgentTool tool : tools) {
            if (!registrationPolicy.allow(tool)) {
                throw new IllegalArgumentException(
                        "agent Tool registration denied: " + tool.name() + " [" + tool.effect() + "]");
            }
            AgentTool previous = indexed.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalArgumentException("duplicate agent tool: " + tool.name());
            }
        }
        this.tools = Map.copyOf(indexed);
    }

    public boolean containsMutating() {
        return tools.values().stream().anyMatch(tool -> tool.effect() == AgentTool.Effect.MUTATING);
    }

    public boolean containsMutating(Collection<String> names) {
        return names.stream().map(this::require)
                .anyMatch(tool -> tool.effect() == AgentTool.Effect.MUTATING);
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
