package io.seekflux.platform.agentruntime.application.spi.capability.event;

import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityEvent;

@FunctionalInterface
public interface CapabilityEventRecorder {

    CapabilityEventRecorder NOOP = ignored -> { };

    void record(CapabilityEvent event);
}
