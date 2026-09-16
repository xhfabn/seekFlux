package io.seekflux.platform.agentruntime.domain.model.context;

import java.time.Instant;

public record ContextEvent(
        Type type,
        String sessionId,
        String requestId,
        int estimatedTokens,
        int budgetTokens,
        String reason,
        Instant occurredAt) {

    public ContextEvent {
        if (type == null || occurredAt == null) {
            throw new IllegalArgumentException("context event type and time are required");
        }
        sessionId = sessionId == null ? "" : sessionId;
        requestId = requestId == null ? "" : requestId;
        reason = reason == null ? "NONE" : reason;
    }

    public enum Type {
        ASSEMBLED_NOOP,
        COMPACTION_TRIGGERED,
        COMPACTION_NOOP,
        COMPACTION_COMMITTED,
        COMPACTION_EXHAUSTED,
        COMPACTION_ASYNC_SCHEDULED,
        COMPACTION_ASYNC_SKIPPED,
        SKELETON_FALLBACK,
        OVERFLOW_RETRY,
        OVERFLOW_EXHAUSTED,
        OUTPUT_ACCEPTED,
        OUTPUT_REPAIR,
        OUTPUT_DEGRADED,
        OUTPUT_EXHAUSTED
    }
}
