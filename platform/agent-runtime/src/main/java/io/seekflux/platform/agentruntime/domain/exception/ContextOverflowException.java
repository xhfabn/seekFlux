package io.seekflux.platform.agentruntime.domain.exception;

public final class ContextOverflowException extends RuntimeException {

    private final int statusCode;

    public ContextOverflowException(int statusCode) {
        super("LLM_CONTEXT_OVERFLOW");
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }
}
