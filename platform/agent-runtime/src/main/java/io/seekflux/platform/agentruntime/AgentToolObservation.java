package io.seekflux.platform.agentruntime;

import java.util.Map;

public record AgentToolObservation(
        String toolCallId,
        String toolName,
        String schemaVersion,
        Map<String, Object> arguments,
        boolean argumentsRepaired,
        AgentToolResult result,
        long tookMillis) {

    public AgentToolObservation {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        if (tookMillis < 0) {
            throw new IllegalArgumentException("tool timing must not be negative");
        }
    }
}
