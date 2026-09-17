package io.seekflux.apps.agentserver.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import io.seekflux.platform.agentruntime.domain.service.wait.WaitTimeoutProcessor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentWaitTimeoutWorkerTest {

    @Test
    void skipsOverlappingScansAndDoesNotBlockTheSchedulerThread() throws Exception {
        Instant now = Instant.parse("2026-09-17T00:00:30Z");
        WaitState expired = new WaitState.AsyncTask(
                1,
                "00000000-0000-0000-0000-000000000020",
                "session",
                "request",
                "turn",
                "00000000-0000-0000-0000-000000000010",
                "call",
                now.minusSeconds(20),
                now.minusSeconds(1),
                "task",
                "webhook");
        AgentRecoveryStore store = new AgentRecoveryStore() {
            @Override public List<WaitState> findExpiredWaits(
                    Instant deadlineExclusive, int limit) {
                return List.of(expired);
            }
        };
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        WaitTimeoutProcessor processor = new WaitTimeoutProcessor(
                store,
                (resolution, publisher) -> {
                    dispatches.incrementAndGet();
                    started.countDown();
                    try {
                        release.await(1, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return RouterResult.completed(null);
                },
                Clock.fixed(now, ZoneOffset.UTC));
        var executor = Executors.newSingleThreadExecutor();
        try {
            AgentWaitTimeoutWorker worker = new AgentWaitTimeoutWorker(processor, 1, executor);

            worker.scan();
            assertTrue(started.await(1, TimeUnit.SECONDS));
            worker.scan();
            release.countDown();
            executor.shutdown();

            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals(1, dispatches.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
