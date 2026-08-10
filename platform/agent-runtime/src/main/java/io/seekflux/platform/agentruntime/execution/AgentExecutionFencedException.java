package io.seekflux.platform.agentruntime.execution;

public final class AgentExecutionFencedException extends IllegalStateException {

    public AgentExecutionFencedException(String sessionId, long fencingToken) {
        super("agent session execution was fenced: session=" + sessionId + ", token=" + fencingToken);
    }
}
