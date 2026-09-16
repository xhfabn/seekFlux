package io.seekflux.platform.agentruntime.application.api.model;

import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;

public record RouterResult(Status status, AgentRunResult outcome, String reason, int queueDepth) {

    public enum Status {
        COMPLETED,
        QUEUED,
        BUSY,
        DUPLICATE,
        REJECTED
    }

    public static RouterResult completed(AgentRunResult outcome) {
        return new RouterResult(Status.COMPLETED, outcome, null, 0);
    }

    public static RouterResult busy() {
        return new RouterResult(Status.BUSY, null, "SESSION_BUSY", 0);
    }

    public static RouterResult duplicate() {
        return new RouterResult(Status.DUPLICATE, null, "DUPLICATE_REQUEST", 0);
    }

    public static RouterResult queued(int queueDepth, boolean duplicate) {
        return new RouterResult(
                Status.QUEUED,
                null,
                duplicate ? "MESSAGE_ALREADY_QUEUED" : "MESSAGE_QUEUED",
                queueDepth);
    }

    public static RouterResult rejected(String reason, int queueDepth) {
        return new RouterResult(Status.REJECTED, null, reason, queueDepth);
    }
}
