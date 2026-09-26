package io.seekflux.agent.infrastructure.mcp;

import java.util.Map;

public record McpRemoteTool(
        String name,
        String description,
        Map<String, Object> inputSchema) {

    public McpRemoteTool {
        name = McpServerConfig.text(name, "MCP remote Tool name", 128);
        description = description == null ? "" : description.trim();
        if (description.length() > 2_048) {
            throw new IllegalArgumentException("MCP Tool description exceeds 2048 characters");
        }
        inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
    }
}
