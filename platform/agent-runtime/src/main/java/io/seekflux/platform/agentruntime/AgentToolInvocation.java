package io.seekflux.platform.agentruntime;

public record AgentToolInvocation(
        String toolName,
        String schemaVersion,
        AgentToolResult result) {
}
