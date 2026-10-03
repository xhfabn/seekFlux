package io.seekflux.platform.agentruntime.domain.model.tool;

import java.util.Objects;

/** Immutable model-visible definition, frozen alongside the execution's Tool versions. */
public record AgentToolDefinition(String name, String description, AgentToolSchema schema) {
    public AgentToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
        name = name.trim();
        description = description == null ? "" : description.trim();
        if (description.length() > 2_048) {
            throw new IllegalArgumentException("tool description exceeds 2048 characters");
        }
        Objects.requireNonNull(schema, "tool schema must not be null");
    }
}
