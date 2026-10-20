package io.seekflux.agent.infrastructure.observability;

import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;

public interface AgentExecutionMetrics {

    AgentExecutionMetrics NOOP = new AgentExecutionMetrics() { };

    default void succeeded(AgentRunResult result, long tookNanos) { }

    default void failed(String agentId, Throwable error, long tookNanos) { }
}
