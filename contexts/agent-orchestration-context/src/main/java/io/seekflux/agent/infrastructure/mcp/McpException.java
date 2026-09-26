package io.seekflux.agent.infrastructure.mcp;

public final class McpException extends RuntimeException {

    private final String code;
    private final boolean connectionFailure;

    public McpException(String code, boolean connectionFailure) {
        this(code, connectionFailure, null);
    }

    public McpException(String code, boolean connectionFailure, Throwable cause) {
        super(code, cause);
        this.code = code;
        this.connectionFailure = connectionFailure;
    }

    public String code() {
        return code;
    }

    public boolean connectionFailure() {
        return connectionFailure;
    }
}
