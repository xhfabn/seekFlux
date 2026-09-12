package io.seekflux.platform.agentruntime.domain.model.tool;

public record AgentToolInvocation(
        String toolName,
        String schemaVersion,
        AgentToolResult result) {
}
