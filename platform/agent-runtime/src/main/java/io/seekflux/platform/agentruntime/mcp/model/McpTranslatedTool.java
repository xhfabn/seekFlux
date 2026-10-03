package io.seekflux.platform.agentruntime.mcp.model;

import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;

/** Schema adaptation result shared by the SPI and the default implementations. */
public record McpTranslatedTool(
        AgentToolSchema schema,
        String remoteSchemaHash,
        String description) {
}
