package io.seekflux.agent.application.exception;

public final class DuplicateAgentRequestException extends RuntimeException {

    public DuplicateAgentRequestException() {
        super("the request id has already been committed for this agent session");
    }
}
