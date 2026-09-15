package io.seekflux.platform.agentruntime.domain.model.sideeffect;

public enum SideEffectStatus {
    PREPARED,
    EXECUTING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    RECONCILED;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == RECONCILED;
    }
}
