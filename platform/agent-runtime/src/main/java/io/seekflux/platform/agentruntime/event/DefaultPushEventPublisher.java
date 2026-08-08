package io.seekflux.platform.agentruntime.event;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

public final class DefaultPushEventPublisher implements PushEventPublisher {

    private final AtomicLong sequence = new AtomicLong();
    private final CopyOnWriteArrayList<BiConsumer<PushEvent, Long>> listeners = new CopyOnWriteArrayList<>();

    public AutoCloseable subscribe(BiConsumer<PushEvent, Long> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public long publish(PushEvent event) {
        long current = sequence.getAndIncrement();
        for (BiConsumer<PushEvent, Long> listener : listeners) {
            try {
                listener.accept(event, current);
            } catch (RuntimeException ignored) {
                // A diagnostic subscriber must never break the agent execution path.
            }
        }
        return current;
    }
}
