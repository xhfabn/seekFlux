package io.seekflux.platform.agentruntime.application.spi.capability.context;

import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;

@FunctionalInterface
public interface ContextEventRecorder {

    ContextEventRecorder NOOP = ignored -> { };

    void record(ContextEvent event);
}
