package io.seekflux.platform.agentruntime.application.spi.capability.observation;

import java.util.List;

/** Adapts the stable event contract to a host-selected format and destination. */
@FunctionalInterface
public interface AgentObservationExporter {
    /** Retries preserve event IDs; destinations should deduplicate by eventId. */
    void export(List<AgentObservationEvent> events) throws Exception;
}
