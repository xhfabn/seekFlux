package io.seekflux.platform.agentruntime.domain.exception;

public final class LlmStreamException extends RuntimeException {

    private final String code;
    private final boolean outputStarted;

    public LlmStreamException(String code, boolean outputStarted, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.outputStarted = outputStarted;
    }

    public String code() {
        return code;
    }

    public boolean outputStarted() {
        return outputStarted;
    }
}
