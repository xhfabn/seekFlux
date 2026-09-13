package io.seekflux.platform.agentruntime.domain.model.execution;

public enum CancellationCause {
    USER_CANCEL,
    STEER,
    AUTHORITY_LOST,
    SHUTDOWN;

    public boolean isSteer() {
        return this == STEER;
    }
}
