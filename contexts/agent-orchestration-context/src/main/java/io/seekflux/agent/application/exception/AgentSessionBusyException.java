package io.seekflux.agent.application.exception;

public final class AgentSessionBusyException extends RuntimeException {

    public AgentSessionBusyException() {
        super("the agent session already has an active execution");
    }
}
