package io.seekflux.platform.agentruntime.application.spi.capability.observation;

/** Must return promptly. Export network IO through AsyncObservationExporter instead. */
@FunctionalInterface
public interface AgentExecutionObserver {
    AgentExecutionObserver NOOP = event -> { };
    void onEvent(AgentObservationEvent event);
}
