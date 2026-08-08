package io.seekflux.platform.agentruntime.event;

@FunctionalInterface
public interface PushEventPublisher {

    PushEventPublisher NOOP = event -> -1;

    long publish(PushEvent event);
}
