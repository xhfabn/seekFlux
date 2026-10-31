package io.seekflux.platform.agentruntime.application.spi.capability.observation;

/** Local, non-blocking filter/redactor. Return null to discard an event before queueing. */
@FunctionalInterface
public interface AgentObservationProcessor {
    AgentObservationProcessor IDENTITY = event -> event;
    AgentObservationEvent process(AgentObservationEvent event);
}
