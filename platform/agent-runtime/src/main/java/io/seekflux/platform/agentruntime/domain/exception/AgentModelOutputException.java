package io.seekflux.platform.agentruntime.domain.exception;

public final class AgentModelOutputException extends RuntimeException {

    private final String code;

    public AgentModelOutputException(String code, Throwable cause) {
        super(code, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
