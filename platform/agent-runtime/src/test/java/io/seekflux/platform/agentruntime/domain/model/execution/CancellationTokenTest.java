package io.seekflux.platform.agentruntime.domain.model.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {

    @Test
    void childObservesParentCancellationWithoutCancellingParent() {
        CancellationToken parent = new CancellationToken();
        CancellationToken child = parent.child();

        child.cancel(CancellationCause.USER_CANCEL);

        assertFalse(parent.isCancelled());
        assertEquals(CancellationCause.USER_CANCEL, child.cause());

        CancellationToken sibling = parent.child();
        parent.cancel(CancellationCause.SHUTDOWN);

        assertEquals(CancellationCause.SHUTDOWN, sibling.cause());
        assertEquals(CancellationCause.USER_CANCEL, child.cause());
    }

    @Test
    void firstObservedCauseIsTheStableLinearizationResult() {
        CancellationToken token = new CancellationToken();

        token.cancel(CancellationCause.USER_CANCEL);
        token.cancel(CancellationCause.STEER);

        assertEquals(CancellationCause.USER_CANCEL, token.cause());
        assertFalse(token.isSteer());
    }

    @Test
    void remoteSignalIsReadOnceAndCarriesOneAtomicCause() {
        AtomicInteger polls = new AtomicInteger();
        CancellationSignalStore signals = new CancellationSignalStore() {
            @Override
            public CancelSignal poll(String sessionId, Instant taskStartedAt) {
                polls.incrementAndGet();
                return new CancelSignal(true, CancellationCause.STEER);
            }

            @Override
            public boolean write(String sessionId, boolean steer, Instant signalTime) {
                return true;
            }
        };
        CancellationToken token = new CancellationToken(
                "session", Instant.parse("2026-09-13T00:00:00Z"), signals, Duration.ZERO);

        assertTrue(token.isCancelled());
        assertTrue(token.isSteer());
        assertEquals(CancellationCause.STEER, token.cause());
        assertEquals(1, polls.get());
    }

    @Test
    void noSignalLeavesCauseEmpty() {
        CancellationToken token = new CancellationToken();

        assertNull(token.cause());
        assertFalse(token.isCancelled());
    }
}
