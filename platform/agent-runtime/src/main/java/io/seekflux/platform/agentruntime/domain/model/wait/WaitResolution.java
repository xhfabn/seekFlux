package io.seekflux.platform.agentruntime.domain.model.wait;

import java.time.Instant;
import java.util.Map;

/** Idempotent external decision used to resume one suspended execution. */
public record WaitResolution(
        int schemaVersion,
        String resolutionId,
        String waitId,
        String sessionId,
        String requestId,
        String turnId,
        Outcome outcome,
        Map<String, Object> output,
        String errorCode,
        String resolvedBy,
        Instant resolvedAt) {

    public WaitResolution(
            int schemaVersion,
            String resolutionId,
            String waitId,
            String sessionId,
            String requestId,
            String turnId,
            Outcome outcome,
            Map<String, Object> output,
            String errorCode,
            Instant resolvedAt) {
        this(schemaVersion, resolutionId, waitId, sessionId, requestId, turnId,
                outcome, output, errorCode, "system", resolvedAt);
    }

    public WaitResolution {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("wait resolution schema version must be positive");
        }
        requireText(resolutionId, "resolution id");
        requireText(waitId, "wait id");
        requireText(sessionId, "session id");
        requireText(requestId, "request id");
        requireText(turnId, "turn id");
        requireText(resolvedBy, "wait resolution actor");
        if (outcome == null || resolvedAt == null) {
            throw new IllegalArgumentException("wait outcome and resolution time are required");
        }
        output = output == null ? Map.of() : Map.copyOf(output);
        if ((outcome == Outcome.FAILED || outcome == Outcome.TIMED_OUT)
                && (errorCode == null || errorCode.isBlank())) {
            throw new IllegalArgumentException("failed or timed out wait requires an error code");
        }
    }

    public boolean resumesToolExecution() {
        return outcome == Outcome.APPROVED;
    }

    public boolean suppliesToolResult() {
        return outcome == Outcome.COMPLETED;
    }

    /** The resolution id is the idempotency key; server receipt time is deliberately excluded. */
    public boolean sameDecisionAs(WaitResolution other) {
        return other != null
                && schemaVersion == other.schemaVersion
                && resolutionId.equals(other.resolutionId)
                && waitId.equals(other.waitId)
                && sessionId.equals(other.sessionId)
                && requestId.equals(other.requestId)
                && turnId.equals(other.turnId)
                && outcome == other.outcome
                && output.equals(other.output)
                && java.util.Objects.equals(errorCode, other.errorCode)
                && resolvedBy.equals(other.resolvedBy);
    }

    public enum Outcome {
        APPROVED,
        DENIED,
        COMPLETED,
        TIMED_OUT,
        CANCELLED,
        FAILED
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
