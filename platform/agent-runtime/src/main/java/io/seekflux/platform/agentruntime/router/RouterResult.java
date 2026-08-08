package io.seekflux.platform.agentruntime.router;

import io.seekflux.platform.agentruntime.AgentRunResult;

public record RouterResult(Status status, AgentRunResult outcome, String reason) {

    public enum Status {
        COMPLETED,
        BUSY,
        DUPLICATE,
        REJECTED
    }

    public static RouterResult completed(AgentRunResult outcome) {
        return new RouterResult(Status.COMPLETED, outcome, null);
    }

    public static RouterResult busy() {
        return new RouterResult(Status.BUSY, null, "SESSION_BUSY");
    }

    public static RouterResult duplicate() {
        return new RouterResult(Status.DUPLICATE, null, "DUPLICATE_REQUEST");
    }
}
