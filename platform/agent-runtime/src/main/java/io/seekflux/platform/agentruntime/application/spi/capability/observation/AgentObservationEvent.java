package io.seekflux.platform.agentruntime.application.spi.capability.observation;

import java.time.Instant;
import java.util.Map;

public record AgentObservationEvent(
        int schemaVersion, String eventId, Identity identity, Kind kind, Phase phase,
        String name, Instant occurredAt, long durationNanos, String outcome,
        Map<String, Object> data) {
    public AgentObservationEvent {
        data = Map.copyOf(data);
    }

    public record Identity(String agentRunId, String requestId, String sessionId, String turnId,
            String observationId, String parentObservationId, String toolCallId, String attemptId) { }

    public enum Kind { RUN, PLAN, CONTEXT, MODEL, TOOL, TOOL_PREPARATION, RECONCILIATION, RUNTIME_EVENT }
    public enum Phase { STARTED, COMPLETED, FAILED, CANCELLED, EVENT }
}
