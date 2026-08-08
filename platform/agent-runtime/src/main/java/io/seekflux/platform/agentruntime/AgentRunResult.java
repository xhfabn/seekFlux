package io.seekflux.platform.agentruntime;

import java.util.Map;

public record AgentRunResult(
        AgentTerminalState state,
        Map<String, Object> output,
        String clarification,
        String fallbackReason,
        boolean degraded,
        AgentRunTrace trace) {

    public AgentRunResult {
        output = output == null ? Map.of() : Map.copyOf(output);
    }
}
