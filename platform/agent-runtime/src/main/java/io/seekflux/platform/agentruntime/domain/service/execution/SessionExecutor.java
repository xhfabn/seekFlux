package io.seekflux.platform.agentruntime.domain.service.execution;

import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
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
import io.seekflux.platform.agentruntime.domain.model.session.QueueCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.QueuedMessageBatch;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryPoint;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolutionResult;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResumeResult;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    private final SteerQueuePolicy steerQueuePolicy;
    private final CapabilityResolver capabilityResolver;
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
                AgentRecoveryStore.NOOP, RecoveryFaultInjector.NONE,
                new SteerQueuePolicy(SteerQueuePolicy.DEFAULT_MAX_DEPTH),
                CapabilityResolver.legacy());
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
                AgentRecoveryStore.NOOP, RecoveryFaultInjector.NONE,
                new SteerQueuePolicy(SteerQueuePolicy.DEFAULT_MAX_DEPTH),
                CapabilityResolver.legacy());
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
        this(authorityStore, sessions, loop, renewalScheduler, clock, cancellationSignals,
                remoteCancelPollInterval, shutdownGracePeriod, recoveryStore,
                recoveryFaultInjector,
                new SteerQueuePolicy(SteerQueuePolicy.DEFAULT_MAX_DEPTH),
                CapabilityResolver.legacy());
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
            RecoveryFaultInjector recoveryFaultInjector,
            SteerQueuePolicy steerQueuePolicy) {
        this(authorityStore, sessions, loop, renewalScheduler, clock, cancellationSignals,
                remoteCancelPollInterval, shutdownGracePeriod, recoveryStore,
                recoveryFaultInjector, steerQueuePolicy, CapabilityResolver.legacy());
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
            RecoveryFaultInjector recoveryFaultInjector,
            SteerQueuePolicy steerQueuePolicy,
            CapabilityResolver capabilityResolver) {
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
        this.steerQueuePolicy = steerQueuePolicy == null
                ? new SteerQueuePolicy(SteerQueuePolicy.DEFAULT_MAX_DEPTH)
                : steerQueuePolicy;
        this.capabilityResolver = capabilityResolver == null
                ? CapabilityResolver.legacy() : capabilityResolver;
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
        AtomicReference<CancellationToken> activeToken = new AtomicReference<>(token);
        cancellationTokens.put(sessionId, token);
        activeRunStarted();
        ScheduledFuture<?> renewal = renewalScheduler.scheduleAtFixedRate(
                () -> {
                    if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                        CancellationToken current = activeToken.get();
                        if (current != null) {
                            current.cancel(CancellationCause.AUTHORITY_LOST);
                        }
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
            AgentRunResult result = executeSegment(
                    sessionId, context, publisher, authority, ingressResult, token);
            if (result.state()
                    != io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.WAITING
                    && result.state()
                    != io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.NEED_CLARIFICATION) {
                drainQueuedMessages(
                        sessionId, context, publisher, authority, activeToken, result);
            }
            return result;
        } finally {
            renewal.cancel(true);
            cancellationTokens.remove(sessionId);
            authority.close();
            activeRunFinished();
        }
    }

    public QueueCommitResult commitQueuedMessage(
            RuntimeContext context,
            PushEventPublisher publisher,
            boolean interruptCurrentSegment) {
        AgentRunRequest sanitized = steerQueuePolicy.sanitize(context.request());
        Instant queuedAt = clock.instant();
        QueueCommitResult result = sessions.enqueue(
                sanitized,
                steerQueuePolicy.sanitizeFeatures(context.features()),
                steerQueuePolicy.maxDepth(),
                queuedAt);
        if (result.status() == QueueCommitResult.Status.COMMITTED) {
            publisher.publish(new PushEvent.MessageQueued(
                    "queued:" + sanitized.requestId(),
                    queuedAt,
                    messageId(sanitized),
                    sanitized.requestId(),
                    sanitized.turnId(),
                    result.queueDepth()));
        }
        if (interruptCurrentSegment
                && result.queueDepth() > 0
                && result.status() == QueueCommitResult.Status.COMMITTED) {
            cancel(sanitized.sessionId(), CancellationCause.STEER, queuedAt);
        }
        return result;
    }

    public boolean drainPendingIfIdle(
            String sessionId,
            RuntimeContext baseContext,
            PushEventPublisher publisher) {
        java.util.Optional<ExecutionAuthority> acquired = tryAcquireExecution(sessionId);
        if (acquired.isEmpty()) {
            return false;
        }
        ExecutionAuthority authority = acquired.get();
        AtomicReference<CancellationToken> activeToken = new AtomicReference<>();
        activeRunStarted();
        ScheduledFuture<?> renewal = renewalScheduler.scheduleAtFixedRate(
                () -> {
                    if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                        CancellationToken current = activeToken.get();
                        if (current != null) {
                            current.cancel(CancellationCause.AUTHORITY_LOST);
                        }
                    }
                },
                AUTHORITY_RENEW_MILLIS,
                AUTHORITY_RENEW_MILLIS,
                TimeUnit.MILLISECONDS);
        try {
            if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                throw new AgentExecutionFencedException(sessionId, authority.fencingToken());
            }
            drainQueuedMessages(
                    sessionId, baseContext, publisher, authority, activeToken, null);
            return true;
        } finally {
            renewal.cancel(true);
            cancellationTokens.remove(sessionId);
            authority.close();
            activeRunFinished();
        }
    }

    public WaitResumeResult resumeWait(
            WaitResolution resolution,
            RuntimeContext context,
            PushEventPublisher publisher) {
        java.util.Optional<ExecutionAuthority> acquired = tryAcquireExecution(resolution.sessionId());
        if (acquired.isEmpty()) {
            return WaitResumeResult.of(WaitResumeResult.Status.BUSY);
        }
        ExecutionAuthority authority = acquired.get();
        try {
            WaitResolutionResult committed = recoveryStore.resolveWait(
                    resolution, authority.fencingToken(), clock.instant());
            if (committed.status() == WaitResolutionResult.Status.MISSING) {
                authority.close();
                return WaitResumeResult.of(WaitResumeResult.Status.MISSING);
            }
            if (committed.status() == WaitResolutionResult.Status.CONFLICT) {
                authority.close();
                return WaitResumeResult.of(WaitResumeResult.Status.CONFLICT);
            }
            if (committed.status() == WaitResolutionResult.Status.DUPLICATE) {
                AgentSession session = sessions.restoreFresh(resolution.sessionId())
                        .orElseThrow(() -> new IllegalStateException(
                                "wait session disappeared during duplicate resolution"));
                if (session.status() == io.seekflux.platform.agentruntime.domain.model.session.AgentSessionStatus.COMPLETED) {
                    authority.close();
                    return WaitResumeResult.of(WaitResumeResult.Status.DUPLICATE);
                }
            }
            publisher.publish(new PushEvent.WaitResolved(
                    "wait:" + resolution.waitId(), clock.instant(), resolution));
            AgentRunResult outcome = run(
                    resolution.sessionId(),
                    context,
                    publisher,
                    authority,
                    IngressCommitResult.RECOVERED);
            return WaitResumeResult.completed(outcome);
        } catch (RuntimeException error) {
            authority.close();
            throw error;
        }
    }

    private AgentRunResult executeSegment(
            String sessionId,
            RuntimeContext context,
            PushEventPublisher publisher,
            ExecutionAuthority authority,
            IngressCommitResult ingressResult,
            CancellationToken token) {
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
                    && recoveryPlan.checkpoint().messageCutoff() != fresh.position()
                    && recoveryPlan.checkpoint().boundary()
                            != io.seekflux.platform.agentruntime.domain.model.recovery.CheckpointBoundary.SUSPENDED
                    && !fresh.hasOnlyQueuedEventsAfter(recoveryPlan.checkpoint().messageCutoff())) {
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
    }

    private void drainQueuedMessages(
            String sessionId,
            RuntimeContext initialContext,
            PushEventPublisher publisher,
            ExecutionAuthority authority,
            AtomicReference<CancellationToken> activeToken,
            AgentRunResult previousResult) {
        RuntimeContext baseContext = initialContext;
        while (!closing) {
            if (!authority.renew(AUTHORITY_TTL_MILLIS)) {
                CancellationToken current = activeToken.get();
                if (current != null) {
                    current.cancel(CancellationCause.AUTHORITY_LOST);
                }
                throw new AgentExecutionFencedException(sessionId, authority.fencingToken());
            }
            AgentSession fresh = sessions.restoreFresh(sessionId)
                    .orElseThrow(() -> new IllegalStateException("agent session disappeared during drain"));
            WorkspaceEvent.QueuedUserMessage promoted = fresh.promotedQueuedExecution().orElse(null);
            QueuedMessageBatch batch;
            IngressCommitResult ingressResult;
            if (promoted != null) {
                batch = new QueuedMessageBatch(List.of(promoted));
                ingressResult = IngressCommitResult.RECOVERED;
            } else {
                java.util.Optional<QueuedMessageBatch> pending = sessions.promoteQueued(
                        sessionId, authority.fencingToken(), clock.instant());
                if (pending.isEmpty()) {
                    return;
                }
                batch = pending.get();
                ingressResult = IngressCommitResult.COMMITTED;
                recoveryFaultInjector.at(RecoveryPoint.AFTER_QUEUED_MESSAGES_PROMOTED);
            }

            cancellationSignals.clearSteerThrough(sessionId, batch.signalCutoff());
            AgentRunRequest request = steerQueuePolicy.sanitize(batch.last().request());
            if (previousResult != null
                    && previousResult.state() == io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.CANCELLED
                    && CancellationCause.STEER.name().equals(previousResult.cancellationReason())) {
                publisher.publish(new PushEvent.Steered(
                        previousResult.trace().agentRunId(),
                        clock.instant(),
                        request.requestId(),
                        batch.messages().size()));
            }

            RuntimeContext drainContext = baseContext.forQueuedRequest(
                    request,
                    batch.last().persistentFeatures(),
                    capabilityResolver.resolve(
                            baseContext.definition(), fresh.capabilityState(),
                            request.capabilities()));
            CancellationToken drainToken = new CancellationToken(
                    sessionId,
                    batch.signalCutoff(),
                    cancellationSignals,
                    remoteCancelPollInterval);
            activeToken.set(drainToken);
            cancellationTokens.put(sessionId, drainToken);
            previousResult = executeSegment(
                    sessionId,
                    drainContext,
                    publisher,
                    authority,
                    ingressResult,
                    drainToken);
            baseContext = drainContext;
        }
    }

    private static String messageId(AgentRunRequest request) {
        String identity = request.sessionId() + ":" + request.requestId()
                + ":" + request.turnId() + ":user";
        return UUID.nameUUIDFromBytes(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    public boolean cancel(String sessionId, boolean steer) {
        return cancel(sessionId, steer ? CancellationCause.STEER : CancellationCause.USER_CANCEL);
    }

    public boolean cancel(String sessionId, CancellationCause cause) {
        return cancel(sessionId, cause, clock.instant());
    }

    private boolean cancel(String sessionId, CancellationCause cause, Instant signalTime) {
        CancellationToken token = cancellationTokens.get(sessionId);
        boolean local = token != null;
        if (local) {
            token.cancel(cause);
        }
        boolean distributed = cancellationSignals.write(sessionId, cause, signalTime);
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
