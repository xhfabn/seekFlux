package io.seekflux.platform.agentruntime.domain.exception;

public final class UnsafeToolRecoveryException extends IllegalStateException {

    private final String sessionId;
    private final String toolCallId;

    public UnsafeToolRecoveryException(String sessionId, String toolCallId) {
        super("MUTATING_TOOL_STATE_UNKNOWN: session=" + sessionId + ", toolCallId=" + toolCallId);
        this.sessionId = sessionId;
        this.toolCallId = toolCallId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String toolCallId() {
        return toolCallId;
    }
}
