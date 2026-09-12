package io.seekflux.platform.agentruntime.application.spi.capability.event;

import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;

@FunctionalInterface
public interface PushEventPublisher {

    PushEventPublisher NOOP = event -> -1;

    long publish(PushEvent event);
}
