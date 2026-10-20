package io.seekflux.agent;

public final class DuplicateAgentRequestException extends RuntimeException {

    public DuplicateAgentRequestException() {
        super("the request id has already been committed for this agent session");
    }
}
