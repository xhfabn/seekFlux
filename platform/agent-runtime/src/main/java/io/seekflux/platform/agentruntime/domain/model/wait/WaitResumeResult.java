package io.seekflux.platform.agentruntime.domain.model.wait;

import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;

public record WaitResumeResult(Status status, AgentRunResult outcome) {

    public static WaitResumeResult completed(AgentRunResult outcome) {
        return new WaitResumeResult(Status.COMPLETED, outcome);
    }

    public static WaitResumeResult of(Status status) {
        return new WaitResumeResult(status, null);
    }

    public enum Status {
        COMPLETED,
        BUSY,
        DUPLICATE,
        CONFLICT,
        MISSING
    }
}
