package io.seekflux.platform.agentruntime.mcp.model;

import java.time.Instant;

public record McpEvent(
        int schemaVersion,
        Type eventType,
        String serverId,
        String toolName,
        Outcome outcome,
        String reason,
        long durationMillis,
        int activeToolCount,
        Instant eventTime) {

    public McpEvent {
        if (schemaVersion != 1 || eventType == null || outcome == null || eventTime == null) {
            throw new IllegalArgumentException("invalid MCP event envelope");
        }
    }

    public enum Type {
        DISCOVERY,
        CALL,
        CONNECTION
    }

    public enum Outcome {
        SUCCEEDED,
        REJECTED,
        FAILED,
        CANCELLED
    }
}
