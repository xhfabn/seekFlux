package io.seekflux.platform.agentruntime.domain.service.wait;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WaitTimeoutProcessorTest {

    @Test
    void idempotencyIgnoresServerReceiptTimeButRejectsChangedDecision() {
        Instant now = Instant.parse("2026-09-17T00:00:30Z");
        WaitResolution first = new WaitResolution(
                1, "resolution", "wait", "session", "request", "turn",
                WaitResolution.Outcome.COMPLETED, Map.of("answer", "ok"), null, now);
        WaitResolution retried = new WaitResolution(
                1, "resolution", "wait", "session", "request", "turn",
                WaitResolution.Outcome.COMPLETED, Map.of("answer", "ok"), null,
                now.plusSeconds(1));
        WaitResolution changed = new WaitResolution(
                1, "resolution", "wait", "session", "request", "turn",
                WaitResolution.Outcome.COMPLETED, Map.of("answer", "changed"), null,
                now.plusSeconds(1));

        org.junit.jupiter.api.Assertions.assertTrue(first.sameDecisionAs(retried));
        org.junit.jupiter.api.Assertions.assertFalse(first.sameDecisionAs(changed));
    }

    @Test
    void waitTypeRestrictsWhichResolutionCanAdvanceIt() {
        Instant now = Instant.parse("2026-09-17T00:00:30Z");
        WaitState hitl = new WaitState.Hitl(
                1, "wait-hitl", "session", "request", "turn", "checkpoint", "call",
                now, now.plusSeconds(30), "approve", "publish", Map.of());
        WaitState async = new WaitState.AsyncTask(
                1, "wait-async", "session", "request", "turn", "checkpoint", "call",
                now, now.plusSeconds(30), "task", "webhook");

        org.junit.jupiter.api.Assertions.assertTrue(
                hitl.accepts(WaitResolution.Outcome.APPROVED));
        org.junit.jupiter.api.Assertions.assertFalse(
                hitl.accepts(WaitResolution.Outcome.COMPLETED));
        org.junit.jupiter.api.Assertions.assertTrue(
                async.accepts(WaitResolution.Outcome.COMPLETED));
        org.junit.jupiter.api.Assertions.assertFalse(
                async.accepts(WaitResolution.Outcome.APPROVED));
    }

    @Test
    void dispatchesDeterministicBoundedTimeoutResolutions() {
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
            @Override
            public List<WaitState> findExpiredWaits(Instant deadlineExclusive, int limit) {
                assertEquals(now, deadlineExclusive);
                assertEquals(1, limit);
                return List.of(expired);
            }
        };
        List<WaitResolution> dispatched = new ArrayList<>();
        WaitTimeoutProcessor processor = new WaitTimeoutProcessor(
                store,
                (resolution, publisher) -> {
                    dispatched.add(resolution);
                    return RouterResult.completed(null);
                },
                Clock.fixed(now, ZoneOffset.UTC));

        assertEquals(1, processor.processExpired(1));
        assertEquals(WaitResolution.Outcome.TIMED_OUT, dispatched.getFirst().outcome());
        assertEquals("WAIT_TIMEOUT", dispatched.getFirst().errorCode());
        assertEquals(expired.waitId(), dispatched.getFirst().waitId());
    }
}
