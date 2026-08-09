package io.seekflux.platform.agentruntime.session;

public final class AgentSessionStateConflictException extends RuntimeException {

    public AgentSessionStateConflictException(long expectedVersion) {
        super("agent session state no longer matches base version " + expectedVersion);
    }
}
