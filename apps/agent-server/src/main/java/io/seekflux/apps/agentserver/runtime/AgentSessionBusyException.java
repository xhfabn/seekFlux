package io.seekflux.apps.agentserver.runtime;

public final class AgentSessionBusyException extends RuntimeException {

    public AgentSessionBusyException() {
        super("the agent session already has an active execution");
    }
}
