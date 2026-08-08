package io.seekflux.platform.agentruntime;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AgentRunEvent(
        UUID eventId,
        String agentRunId,
        String requestId,
        String sessionId,
        String turnId,
        int sequence,
        Type type,
        Instant eventTime,
        Map<String, Object> payload) {

    public enum Type {
        RUN_STARTED,
        DECISION_MADE,
        TOOL_COMPLETED,
        RUN_COMPLETED
    }

    public AgentRunEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
