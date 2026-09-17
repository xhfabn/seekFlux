package io.seekflux.platform.agentruntime.application.spi.capability.llm.model;

import java.util.Map;

public record ChatToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema) {

    public ChatToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("chat tool name must not be blank");
        }
        name = name.trim();
        description = description == null ? "" : description.trim();
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
