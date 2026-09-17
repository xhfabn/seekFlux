package io.seekflux.platform.agentruntime.infrastructure.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventRelay;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushFrame;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class DefaultPushEventStreamTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void assignsMonotonicSequenceAndReplaysAfterLastSeenEvent() throws Exception {
        try (DefaultPushEventStream stream = new DefaultPushEventStream(
                4, 4, 2, "node-a", PushEventRelay.LOCAL_ONLY, CLOCK)) {
            var publisher = stream.publisher("session-1", "request-1");
            publisher.publish(event("one"));
            publisher.publish(event("two"));
            publisher.publish(event("three"));

            try (var subscription = stream.subscribe("session-1", 0)) {
                assertFalse(subscription.replayGap());
                assertEquals(1, subscription.poll(Duration.ofMillis(10)).sequence());
                assertEquals(2, subscription.poll(Duration.ofMillis(10)).sequence());
                assertNull(subscription.poll(Duration.ofMillis(10)));
            }
        }
    }

    @Test
    void marksReplayGapAndDisconnectsASlowConsumerAtTheHardLimit() throws Exception {
        try (DefaultPushEventStream stream = new DefaultPushEventStream(
                2, 2, 2, "node-a", PushEventRelay.LOCAL_ONLY, CLOCK)) {
            var publisher = stream.publisher("session-1", "request-1");
            publisher.publish(event("zero"));
            publisher.publish(event("one"));
            publisher.publish(event("two"));
            publisher.publish(event("three"));
            try (var replay = stream.subscribe("session-1", 0)) {
                assertTrue(replay.replayGap());
            }

            try (var slow = stream.subscribe("session-1", 3)) {
                publisher.publish(event("four"));
                publisher.publish(event("five"));
                publisher.publish(event("six"));
                assertTrue(slow.overflowed());
            }
        }
    }

    @Test
    void relaysAcrossInstancesWithoutBroadcastLoop() throws Exception {
        SharedRelay relay = new SharedRelay();
        try (DefaultPushEventStream first = new DefaultPushEventStream(
                    4, 4, 2, "node-a", relay, CLOCK);
                DefaultPushEventStream second = new DefaultPushEventStream(
                    4, 4, 2, "node-b", relay, CLOCK);
                var subscription = second.subscribe("session-1", -1)) {
            first.publisher("session-1", "request-1").publish(event("remote"));

            PushFrame received = subscription.poll(Duration.ofMillis(50));
            assertNotNull(received);
            assertEquals("node-a", received.sourceId());
            assertEquals(1, relay.broadcasts.size());
        }
    }

    @Test
    void refusesUnboundedSessionGrowthWhenEverySessionHasASubscriber() {
        try (DefaultPushEventStream stream = new DefaultPushEventStream(
                1, 1, 1, "node-a", PushEventRelay.LOCAL_ONLY, CLOCK);
                var ignored = stream.subscribe("session-1", -1)) {
            assertThrows(IllegalStateException.class,
                    () -> stream.subscribe("session-2", -1));
        }
    }

    @Test
    void reconnectAfterTerminalCanCompleteWithoutStartingAnotherExecution() {
        try (DefaultPushEventStream stream = new DefaultPushEventStream(
                2, 2, 2, "node-a", PushEventRelay.LOCAL_ONLY, CLOCK)) {
            long terminal = stream.publisher("session-1", "request-1").publish(
                    new PushEvent.LoopCompleted(
                            "run-1", CLOCK.instant(), AgentTerminalState.RESULTS_READY, 10));
            try (var reconnect = stream.subscribe("session-1", terminal)) {
                assertTrue(reconnect.terminalObserved());
            }
        }
    }

    private static PushEvent event(String detail) {
        return new PushEvent.Control("run-1", CLOCK.instant(), "TEST", detail);
    }

    private static final class SharedRelay implements PushEventRelay {
        private final AtomicLong sequence = new AtomicLong();
        private final List<Consumer<PushFrame>> listeners = new CopyOnWriteArrayList<>();
        private final List<PushFrame> broadcasts = new CopyOnWriteArrayList<>();

        @Override
        public long nextSequence(String sessionId) {
            return sequence.getAndIncrement();
        }

        @Override
        public void broadcast(PushFrame frame) {
            broadcasts.add(frame);
            listeners.forEach(listener -> listener.accept(frame));
        }

        @Override
        public AutoCloseable listen(Consumer<PushFrame> listener) {
            listeners.add(listener);
            return () -> listeners.remove(listener);
        }
    }
}
