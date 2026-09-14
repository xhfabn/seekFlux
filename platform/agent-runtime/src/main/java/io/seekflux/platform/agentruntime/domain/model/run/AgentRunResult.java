package io.seekflux.platform.agentruntime.domain.model.run;

import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import java.util.List;
import java.util.Map;

public record AgentRunResult(
        AgentTerminalState state,
        Map<String, Object> output,
        String clarification,
        String fallbackReason,
        String cancellationReason,
        boolean degraded,
        List<AgentMessage> messages,
        AgentRunTrace trace) {

    public AgentRunResult(
            AgentTerminalState state,
            Map<String, Object> output,
            String clarification,
            String fallbackReason,
            String cancellationReason,
            boolean degraded,
            AgentRunTrace trace) {
        this(state, output, clarification, fallbackReason, cancellationReason, degraded, List.of(), trace);
    }

    public AgentRunResult(
            AgentTerminalState state,
            Map<String, Object> output,
            String clarification,
            String fallbackReason,
            boolean degraded,
            AgentRunTrace trace) {
        this(state, output, clarification, fallbackReason, null, degraded, List.of(), trace);
    }

    public AgentRunResult {
        output = output == null ? Map.of() : Map.copyOf(output);
        messages = messages == null ? List.of() : List.copyOf(messages);
        if (state == AgentTerminalState.CANCELLED && cancellationReason == null) {
            throw new IllegalArgumentException("a cancelled run must have a cancellation reason");
        }
        if (state != AgentTerminalState.CANCELLED && cancellationReason != null) {
            throw new IllegalArgumentException("only a cancelled run can have a cancellation reason");
        }
    }
}
