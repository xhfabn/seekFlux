package io.seekflux.platform.agentruntime.domain.exception;

public final class AgentExecutionFencedException extends IllegalStateException {

    public AgentExecutionFencedException(String sessionId, long fencingToken) {
        super("agent session execution was fenced: session=" + sessionId + ", token=" + fencingToken);
    }
}
