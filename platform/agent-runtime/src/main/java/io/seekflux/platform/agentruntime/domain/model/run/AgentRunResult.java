package io.seekflux.platform.agentruntime.domain.model.run;

import java.util.Map;

public record AgentRunResult(
        AgentTerminalState state,
        Map<String, Object> output,
        String clarification,
        String fallbackReason,
        String cancellationReason,
        boolean degraded,
        AgentRunTrace trace) {

    public AgentRunResult(
            AgentTerminalState state,
            Map<String, Object> output,
            String clarification,
            String fallbackReason,
            boolean degraded,
            AgentRunTrace trace) {
        this(state, output, clarification, fallbackReason, null, degraded, trace);
    }

    public AgentRunResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        if (state == AgentTerminalState.CANCELLED && cancellationReason == null) {
            throw new IllegalArgumentException("a cancelled run must have a cancellation reason");
        }
        if (state != AgentTerminalState.CANCELLED && cancellationReason != null) {
            throw new IllegalArgumentException("only a cancelled run can have a cancellation reason");
        }
    }
}
