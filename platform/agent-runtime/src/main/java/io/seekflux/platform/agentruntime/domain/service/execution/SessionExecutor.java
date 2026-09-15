package io.seekflux.platform.agentruntime.domain.service.execution;

import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthority;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.exception.AgentExecutionFencedException;
import io.seekflux.platform.agentruntime.domain.exception.UnsafeToolRecoveryException;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.service.loop.AgentLoop;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeAction;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeIngress;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeSource;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolJournalStatus;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector;
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
    private final AgentRecoveryStore recoveryStore;
    private final RecoveryFaultInjector recoveryFaultInjector;
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
                CancellationSignalStore.NOOP, Duration.ZERO, Duration.ofSeconds(5),
                AgentRecoveryStore.NOOP, RecoveryFaultInjector.NONE);
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
        this(authorityStore, sessions, loop, renewalScheduler, clock, cancellationSignals,
                remoteCancelPollInterval, shutdownGracePeriod,
                AgentRecoveryStore.NOOP, RecoveryFaultInjector.NONE);
    }

    public SessionExecutor(
            ExecutionAuthorityStore authorityStore,
            AgentSessionStore sessions,
            AgentLoop loop,
            ScheduledExecutorService renewalScheduler,
            Clock clock,
            CancellationSignalStore cancellationSignals,
            Duration remoteCancelPollInterval,
            Duration shutdownGracePeriod,
            AgentRecoveryStore recoveryStore,
            RecoveryFaultInjector recoveryFaultInjector) {
        this.authorityStore = authorityStore;
        this.sessions = sessions;
        this.loop = loop;
        this.renewalScheduler = renewalScheduler;
        this.clock = clock;
        this.cancellationSignals = cancellationSignals;
        this.remoteCancelPollInterval = remoteCancelPollInterval;
        this.shutdownGracePeriod = shutdownGracePeriod;
        this.recoveryStore = recoveryStore == null ? AgentRecoveryStore.NOOP : recoveryStore;
        this.recoveryFaultInjector = recoveryFaultInjector == null
                ? RecoveryFaultInjector.NONE : recoveryFaultInjector;
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
        return run(sessionId, context, publisher, authority, IngressCommitResult.COMMITTED);
    }

    public AgentRunResult run(
            String sessionId,
            RuntimeContext context,
            PushEventPublisher publisher,
            ExecutionAuthority authority,
            IngressCommitResult ingressResult) {
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
                        token.cancel(CancellationCause.AUTHORITY_LOST);
                    }
                },
                AUTHORITY_RENEW_MILLIS,
                AUTHORITY_RENEW_MILLIS,
                TimeUnit.MILLISECONDS);
        try {
            if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                token.cancel(CancellationCause.AUTHORITY_LOST);
                throw new AgentExecutionFencedException(sessionId, authority.fencingToken());
            }
            AgentSession fresh = sessions.restoreFresh(sessionId)
                    .orElseThrow(() -> new IllegalStateException("agent session disappeared before execution"));
            ResumeSource resumeSource = ingressResult == IngressCommitResult.RECOVERED
                    ? ResumeSource.CRASH_RECOVERY
                    : ResumeSource.USER_MESSAGE;
            RecoveryPlan recoveryPlan = recoveryStore.commitResume(
                    new ResumeIngress(
                            1,
                            sessionId,
                            context.request().requestId(),
                            context.request().turnId(),
                            resumeSource),
                    authority.fencingToken(),
                    clock.instant());
            if (recoveryPlan.checkpoint() != null
                    && recoveryPlan.checkpoint().messageCutoff() != fresh.position()) {
                throw new IllegalStateException(
                        "checkpoint message cutoff no longer matches the Workspace high-water mark");
            }
            if (recoveryPlan.action() == ResumeAction.FAIL_UNSAFE_PENDING_TOOL) {
                String unsafeCall = recoveryPlan.toolCalls().stream()
                        .filter(call -> call.status() == ToolJournalStatus.UNKNOWN && !call.safeToRetry())
                        .map(call -> call.toolCallId())
                        .findFirst()
                        .orElse("unknown");
                throw new UnsafeToolRecoveryException(sessionId, unsafeCall);
            }
            RuntimeContext executionContext = recoveryPlan.checkpoint() == null
                    ? context
                    : context.withPersistentFeatures(
                            recoveryPlan.checkpoint().persistentFeatures());
            long messageCutoff = recoveryPlan.checkpoint() == null
                    ? fresh.position()
                    : recoveryPlan.checkpoint().messageCutoff();
            AgentRecoveryExecution recovery = new AgentRecoveryExecution(
                    recoveryStore,
                    recoveryPlan,
                    authority.fencingToken(),
                    messageCutoff,
                    executionContext.features(),
                    clock,
                    recoveryFaultInjector);
            //loop 启动入口
            AgentRunResult result = recoveryPlan.action() == ResumeAction.COMMIT_TERMINAL
                    ? recoveryPlan.checkpoint().terminalResult()
                    : loop.run(fresh, executionContext, publisher, token, recovery);
            if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                token.cancel(CancellationCause.AUTHORITY_LOST);
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
        return cancel(sessionId, steer ? CancellationCause.STEER : CancellationCause.USER_CANCEL);
    }

    public boolean cancel(String sessionId, CancellationCause cause) {
        CancellationToken token = cancellationTokens.get(sessionId);
        boolean local = token != null;
        if (local) {
            token.cancel(cause);
        }
        boolean distributed = cancellationSignals.write(sessionId, cause, clock.instant());
        return local || distributed;
    }

    @Override
    public void close() {
        closing = true;
        cancellationTokens.forEach((sessionId, token) -> {
            token.cancel(CancellationCause.SHUTDOWN);
            cancellationSignals.write(sessionId, CancellationCause.SHUTDOWN, clock.instant());
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
