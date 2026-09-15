package io.seekflux.platform.agentruntime.domain.model.recovery;

public enum ToolJournalStatus {
    DECIDED,
    EXECUTING,
    UNKNOWN,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT;

    public boolean terminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
    }
}
