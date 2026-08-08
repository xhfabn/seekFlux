package io.seekflux.platform.agentruntime;

import java.time.Duration;
import java.util.Map;

public record AgentToolContext(
        String agentRunId,
        String toolCallId,
        AgentRunRequest request,
        Map<String, Object> arguments,
        Duration remaining) {

    public AgentToolContext {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
