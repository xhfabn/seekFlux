package io.seekflux.platform.agentruntime.domain.service.wait;

import io.seekflux.platform.agentruntime.application.api.WaitResumeDispatcher;
import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Bounded timeout scanner; callback/timeout races are decided by the durable wait store. */
public final class WaitTimeoutProcessor {

    private final AgentRecoveryStore waits;
    private final WaitResumeDispatcher dispatcher;
    private final Clock clock;

    public WaitTimeoutProcessor(
            AgentRecoveryStore waits,
            WaitResumeDispatcher dispatcher,
            Clock clock) {
        this.waits = waits;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    public int processExpired(int limit) {
        Instant now = clock.instant();
        int completed = 0;
        for (WaitState wait : waits.findExpiredWaits(now, limit)) {
            WaitResolution resolution = new WaitResolution(
                    1,
                    timeoutResolutionId(wait),
                    wait.waitId(),
                    wait.sessionId(),
                    wait.requestId(),
                    wait.turnId(),
                    WaitResolution.Outcome.TIMED_OUT,
                    java.util.Map.of(),
                    "WAIT_TIMEOUT",
                    "system:wait-timeout",
                    now);
            try {
                RouterResult result = dispatcher.dispatch(resolution, PushEventPublisher.NOOP);
                if (result.status() == RouterResult.Status.COMPLETED
                        || result.status() == RouterResult.Status.DUPLICATE) {
                    completed++;
                }
            } catch (RuntimeException ignored) {
                // A later bounded scan retries; the durable resolution remains the race arbiter.
            }
        }
        return completed;
    }

    private static String timeoutResolutionId(WaitState wait) {
        return UUID.nameUUIDFromBytes(("wait-timeout:" + wait.waitId() + ":"
                + wait.deadlineAt()).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
