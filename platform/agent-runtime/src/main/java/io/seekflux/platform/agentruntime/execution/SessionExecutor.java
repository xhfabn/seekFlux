package io.seekflux.platform.agentruntime.execution;

import io.seekflux.platform.agentruntime.AgentRunResult;
import io.seekflux.platform.agentruntime.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.loop.AgentLoop;
import io.seekflux.platform.agentruntime.session.AgentSession;
import io.seekflux.platform.agentruntime.session.AgentSessionStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SessionExecutor implements AutoCloseable {

    public static final long AUTHORITY_TTL_MILLIS = 30_000;
    public static final long AUTHORITY_RENEW_MILLIS = 10_000;

    private final ExecutionAuthorityStore authorityStore;
    private final AgentSessionStore sessions;
    private final AgentLoop loop;
    private final ScheduledExecutorService renewalScheduler;
    private final Clock clock;
    private final CancellationSignalStore cancellationSignals;
    private final Duration remoteCancelPollInterval;
    private final Duration shutdownGracePeriod;
    private final Map<String, CancellationToken> cancellationTokens = new ConcurrentHashMap<>();
    private final Object activeMonitor = new Object();
    private int activeRuns;
    private volatile boolean closing;

    public SessionExecutor(
            ExecutionAuthorityStore authorityStore,
            AgentSessionStore sessions,
            AgentLoop loop,
            ScheduledExecutorService renewalScheduler,
            Clock clock) {
        this(authorityStore, sessions, loop, renewalScheduler, clock,
                CancellationSignalStore.NOOP, Duration.ZERO, Duration.ofSeconds(5));
    }

    public SessionExecutor(
            ExecutionAuthorityStore authorityStore,
            AgentSessionStore sessions,
            AgentLoop loop,
            ScheduledExecutorService renewalScheduler,
            Clock clock,
            CancellationSignalStore cancellationSignals,
            Duration remoteCancelPollInterval,
            Duration shutdownGracePeriod) {
        this.authorityStore = authorityStore;
        this.sessions = sessions;
        this.loop = loop;
        this.renewalScheduler = renewalScheduler;
        this.clock = clock;
        this.cancellationSignals = cancellationSignals;
        this.remoteCancelPollInterval = remoteCancelPollInterval;
        this.shutdownGracePeriod = shutdownGracePeriod;
    }

    public java.util.Optional<ExecutionAuthority> tryAcquireExecution(String sessionId) {
        if (closing) {
            return java.util.Optional.empty();
        }
        String owner = UUID.randomUUID().toString();
        return authorityStore.acquire(sessionId, owner, AUTHORITY_TTL_MILLIS);
    }

    public AgentRunResult run(
            String sessionId,
            RuntimeContext context,
            PushEventPublisher publisher,
            ExecutionAuthority authority) {
        if (closing) {
            authority.close();
            throw new IllegalStateException("agent runtime is shutting down");
        }
        Instant taskStartedAt = clock.instant();
        CancellationToken token = new CancellationToken(
                sessionId, taskStartedAt, cancellationSignals, remoteCancelPollInterval);
        cancellationTokens.put(sessionId, token);
        activeRunStarted();
        ScheduledFuture<?> renewal = renewalScheduler.scheduleAtFixedRate(
                () -> {
                    if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                        token.cancel(false);
                    }
                },
                AUTHORITY_RENEW_MILLIS,
                AUTHORITY_RENEW_MILLIS,
                TimeUnit.MILLISECONDS);
        try {
            if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                token.cancel(false);
                throw new IllegalStateException("agent session execution authority was lost before execution");
            }
            AgentSession fresh = sessions.restoreFresh(sessionId)
                    .orElseThrow(() -> new IllegalStateException("agent session disappeared before execution"));
            AgentRunResult result = loop.run(fresh, context, publisher, token);
            if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                throw new AgentExecutionFencedException(sessionId, authority.fencingToken());
            }
            sessions.appendOutcome(sessionId, result, authority.fencingToken(), clock.instant());
            return result;
        } finally {
            renewal.cancel(true);
            cancellationTokens.remove(sessionId);
            authority.close();
            activeRunFinished();
        }
    }

    public boolean cancel(String sessionId, boolean steer) {
        CancellationToken token = cancellationTokens.get(sessionId);
        boolean local = token != null;
        if (local) {
            token.cancel(steer);
        }
        boolean distributed = cancellationSignals.write(sessionId, steer, clock.instant());
        return local || distributed;
    }

    @Override
    public void close() {
        closing = true;
        cancellationTokens.forEach((sessionId, token) -> {
            cancellationSignals.write(sessionId, false, clock.instant());
            token.cancel(false);
        });
        awaitActiveRuns();
        renewalScheduler.shutdownNow();
    }

    private void activeRunStarted() {
        synchronized (activeMonitor) {
            activeRuns++;
        }
    }

    private void activeRunFinished() {
        synchronized (activeMonitor) {
            activeRuns--;
            activeMonitor.notifyAll();
        }
    }

    private void awaitActiveRuns() {
        long deadline = System.nanoTime() + shutdownGracePeriod.toNanos();
        synchronized (activeMonitor) {
            while (activeRuns > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return;
                }
                try {
                    long millis = Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining));
                    activeMonitor.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
