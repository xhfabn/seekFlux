package io.seekflux.platform.agentruntime.domain.service.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seekflux.platform.agentruntime.application.command.AgentIngressMode;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthority;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.exception.AgentExecutionFencedException;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunTrace;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.QueueCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.QueuedMessageBatch;
import io.seekflux.platform.agentruntime.domain.model.session.SessionStatePatch;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import io.seekflux.platform.agentruntime.domain.service.loop.AgentLoop;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryPoint;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SessionExecutorSteerQueueTest {

    @Test
    void persistsBeforeInterruptAndDrainsConcurrentMessagesInFifoOrder() throws Exception {
        TickingClock clock = new TickingClock();
        List<String> ordering = new ArrayList<>();
        MemorySignals signals = new MemorySignals(ordering);
        MemorySessions sessions = MemorySessions.running(currentRequest(), ordering);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<RuntimeContext> drainedContext = new AtomicReference<>();
        List<List<String>> histories = new ArrayList<>();
        AgentLoop loop = loop((session, context, publisher, token) -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                while (!token.isCancelled()) {
                    Thread.onSpinWait();
                }
                await(releaseFirst);
                return outcome(context, call, AgentTerminalState.CANCELLED, token.cause());
            }
            drainedContext.set(context);
            histories.add(session.events().stream()
                    .filter(WorkspaceEvent.UserMessage.class::isInstance)
                    .map(WorkspaceEvent.UserMessage.class::cast)
                    .map(WorkspaceEvent.UserMessage::text)
                    .toList());
            return outcome(context, call, AgentTerminalState.RESULTS_READY, null);
        });
        List<PushEvent> pushed = new ArrayList<>();
        SessionExecutor executor = executor(
                sessions, loop, signals, clock, new SteerQueuePolicy(8));
        try {
            CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(
                    () -> executor.run(
                            "session", context(currentRequest()), publisher(pushed), authority(11)));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

            QueueCommitResult first = executor.commitQueuedMessage(
                    context(queuedRequest(
                            "request-2", "turn-2", "second",
                            Map.of("userId", "user-2", "modelOverride", "forbidden"))),
                    publisher(pushed),
                    true);
            QueueCommitResult second = executor.commitQueuedMessage(
                    context(
                            queuedRequest(
                                    "request-3", "turn-3", "third", Map.of("userId", "user-3")),
                            Map.of("persistent", "latest", "modelOverride", "forbidden")),
                    publisher(pushed),
                    true);
            releaseFirst.countDown();

            AgentRunResult initial = running.get(2, TimeUnit.SECONDS);
            assertEquals(AgentTerminalState.CANCELLED, initial.state());
            assertEquals(CancellationCause.STEER.name(), initial.cancellationReason());
            assertEquals(1, first.queueDepth());
            assertEquals(2, second.queueDepth());
            assertEquals(2, calls.get());
            assertEquals(List.of("current", "second", "third"), histories.getFirst());
            assertEquals("request-3", drainedContext.get().request().requestId());
            assertEquals("user-3", drainedContext.get().request().attributes().get("userId"));
            assertFalse(drainedContext.get().request().attributes().containsKey("modelOverride"));
            assertEquals("latest", drainedContext.get().features().get("persistent"));
            assertFalse(drainedContext.get().features().containsKey("modelOverride"));
            assertTrue(sessions.restoreFresh("session").orElseThrow().queuedMessages().isEmpty());
            assertTrue(pushed.stream().filter(PushEvent.MessageQueued.class::isInstance).count() == 2);
            assertTrue(pushed.stream().anyMatch(PushEvent.Steered.class::isInstance));
            assertEquals(List.of("enqueue", "signal", "enqueue", "signal"),
                    ordering.subList(0, 4));
        } finally {
            releaseFirst.countDown();
            executor.close();
        }
    }

    @Test
    void aRealCancelAfterSteerIsPreservedForTheDrainedSegment() throws Exception {
        TickingClock clock = new TickingClock();
        MemorySignals signals = new MemorySignals(new ArrayList<>());
        MemorySessions sessions = MemorySessions.running(currentRequest(), new ArrayList<>());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AgentLoop loop = loop((session, context, publisher, token) -> {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                while (!token.isCancelled()) {
                    Thread.onSpinWait();
                }
                await(releaseFirst);
            }
            CancellationCause cause = token.cause();
            return outcome(
                    context,
                    call,
                    cause == null ? AgentTerminalState.RESULTS_READY : AgentTerminalState.CANCELLED,
                    cause);
        });
        SessionExecutor executor = executor(
                sessions, loop, signals, clock, new SteerQueuePolicy(8));
        try {
            CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(
                    () -> executor.run(
                            "session", context(currentRequest()), PushEventPublisher.NOOP, authority(12)));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            executor.commitQueuedMessage(
                    context(queuedRequest(
                            "request-2", "turn-2", "second", Map.of())),
                    PushEventPublisher.NOOP,
                    true);
            executor.cancel("session", CancellationCause.USER_CANCEL);
            releaseFirst.countDown();
            running.get(2, TimeUnit.SECONDS);

            assertEquals(2, calls.get());
            assertEquals(
                    List.of(CancellationCause.STEER.name(), CancellationCause.USER_CANCEL.name()),
                    sessions.cancellationReasons());
        } finally {
            releaseFirst.countDown();
            executor.close();
        }
    }

    @Test
    void recoversAPromotedBatchWithoutPromotingOrAppendingTheUserMessageAgain() {
        AgentRunRequest queued = queuedRequest(
                "request-2", "turn-2", "recover me", Map.of("userId", "user-2"));
        MemorySessions sessions = MemorySessions.promoted(queued);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> executedRequest = new AtomicReference<>();
        AgentLoop loop = loop((session, context, publisher, token) -> {
            calls.incrementAndGet();
            executedRequest.set(context.request().requestId());
            return outcome(context, 1, AgentTerminalState.RESULTS_READY, null);
        });
        SessionExecutor executor = executor(
                sessions,
                loop,
                new MemorySignals(new ArrayList<>()),
                new TickingClock(),
                new SteerQueuePolicy(8));
        try {
            assertTrue(executor.drainPendingIfIdle(
                    "session", context(currentRequest()), PushEventPublisher.NOOP));
            assertEquals(1, calls.get());
            assertEquals("request-2", executedRequest.get());
            long promotedUsers = sessions.restoreFresh("session").orElseThrow().events().stream()
                    .filter(WorkspaceEvent.UserMessage.class::isInstance)
                    .count();
            assertEquals(1, promotedUsers);
        } finally {
            executor.close();
        }
    }

    @Test
    void duplicateMessagesDoNotConsumeCapacityAndTheQueueIsBounded() {
        MemorySessions sessions = MemorySessions.running(currentRequest(), new ArrayList<>());
        SessionExecutor executor = executor(
                sessions,
                immediateLoop(),
                new MemorySignals(new ArrayList<>()),
                new TickingClock(),
                new SteerQueuePolicy(2));
        try {
            RuntimeContext second = context(queuedRequest(
                    "request-2", "turn-2", "second", Map.of()));
            assertEquals(QueueCommitResult.Status.COMMITTED,
                    executor.commitQueuedMessage(
                            second, PushEventPublisher.NOOP, false).status());
            assertEquals(QueueCommitResult.Status.DUPLICATE_PENDING,
                    executor.commitQueuedMessage(
                            second, PushEventPublisher.NOOP, false).status());
            assertEquals(QueueCommitResult.Status.COMMITTED,
                    executor.commitQueuedMessage(
                            context(queuedRequest(
                                    "request-3", "turn-3", "third", Map.of())),
                            PushEventPublisher.NOOP,
                            false).status());
            QueueCommitResult full = executor.commitQueuedMessage(
                    context(queuedRequest(
                            "request-4", "turn-4", "fourth", Map.of())),
                    PushEventPublisher.NOOP,
                    false);
            assertEquals(QueueCommitResult.Status.FULL, full.status());
            assertEquals(2, full.queueDepth());
        } finally {
            executor.close();
        }
    }

    @Test
    void ownerLossStopsTheWholeDrainAndANewOwnerConsumesThePersistedQueue() {
        TickingClock clock = new TickingClock();
        MemorySessions sessions = MemorySessions.running(currentRequest(), new ArrayList<>());
        AgentRunRequest queued = queuedRequest(
                "request-2", "turn-2", "survives fencing", Map.of());
        sessions.enqueue(queued, Map.of("recoveredFeature", "yes"), 8, clock.instant());
        AtomicInteger renewals = new AtomicInteger();
        ExecutionAuthority losingAuthority = new ExecutionAuthority() {
            @Override public long fencingToken() { return 30; }
            @Override public boolean renew(long ttlMillis) {
                return renewals.incrementAndGet() <= 2;
            }
            @Override public void close() { }
        };
        SessionExecutor firstOwner = executor(
                sessions,
                immediateLoop(),
                new MemorySignals(new ArrayList<>()),
                clock,
                new SteerQueuePolicy(8));
        try {
            assertThrows(AgentExecutionFencedException.class, () -> firstOwner.run(
                    "session",
                    context(currentRequest()),
                    PushEventPublisher.NOOP,
                    losingAuthority));
            assertEquals(1, sessions.restoreFresh("session").orElseThrow().queuedMessages().size());
        } finally {
            firstOwner.close();
        }

        AtomicReference<String> recovered = new AtomicReference<>();
        SessionExecutor nextOwner = executor(
                sessions,
                loop((session, context, publisher, token) -> {
                    recovered.set(context.request().requestId());
                    return outcome(context, 2, AgentTerminalState.RESULTS_READY, null);
                }),
                new MemorySignals(new ArrayList<>()),
                clock,
                new SteerQueuePolicy(8));
        try {
            assertTrue(nextOwner.drainPendingIfIdle(
                    "session", context(currentRequest()), PushEventPublisher.NOOP));
            assertEquals("request-2", recovered.get());
            assertTrue(sessions.restoreFresh("session").orElseThrow().queuedMessages().isEmpty());
        } finally {
            nextOwner.close();
        }
    }

    @Test
    void crashAfterPromotionRecoversThePromotedRequestWithoutDuplicatingItsUserEvent() {
        TickingClock clock = new TickingClock();
        MemorySessions sessions = MemorySessions.completed(currentRequest(), new ArrayList<>());
        AgentRunRequest queued = queuedRequest(
                "request-2", "turn-2", "resume after promotion", Map.of());
        sessions.enqueue(queued, Map.of("recoveredFeature", "yes"), 8, clock.instant());
        RecoveryFaultInjector crashAfterPromotion = point -> {
            if (point == RecoveryPoint.AFTER_QUEUED_MESSAGES_PROMOTED) {
                throw new IllegalStateException("simulated drain crash");
            }
        };
        SessionExecutor crashing = executor(
                sessions,
                immediateLoop(),
                new MemorySignals(new ArrayList<>()),
                clock,
                new SteerQueuePolicy(8),
                crashAfterPromotion);
        try {
            assertThrows(IllegalStateException.class, () -> crashing.drainPendingIfIdle(
                    "session", context(currentRequest()), PushEventPublisher.NOOP));
            AgentSession promoted = sessions.restoreFresh("session").orElseThrow();
            assertTrue(promoted.queuedMessages().isEmpty());
            assertEquals("request-2",
                    promoted.promotedQueuedExecution().orElseThrow().request().requestId());
        } finally {
            crashing.close();
        }

        AtomicInteger recoveredCalls = new AtomicInteger();
        AtomicReference<Map<String, Object>> recoveredFeatures = new AtomicReference<>();
        SessionExecutor recovering = executor(
                sessions,
                loop((session, context, publisher, token) -> {
                    recoveredCalls.incrementAndGet();
                    recoveredFeatures.set(context.features());
                    return outcome(context, 2, AgentTerminalState.RESULTS_READY, null);
                }),
                new MemorySignals(new ArrayList<>()),
                clock,
                new SteerQueuePolicy(8));
        try {
            assertTrue(recovering.drainPendingIfIdle(
                    "session", context(currentRequest()), PushEventPublisher.NOOP));
            assertEquals(1, recoveredCalls.get());
            assertEquals(Map.of("recoveredFeature", "yes"), recoveredFeatures.get());
            long userEvents = sessions.restoreFresh("session").orElseThrow().events().stream()
                    .filter(WorkspaceEvent.UserMessage.class::isInstance)
                    .map(WorkspaceEvent.UserMessage.class::cast)
                    .filter(user -> user.requestId().equals("request-2"))
                    .count();
            assertEquals(1, userEvents);
        } finally {
            recovering.close();
        }
    }

    private static SessionExecutor executor(
            MemorySessions sessions,
            AgentLoop loop,
            CancellationSignalStore signals,
            Clock clock,
            SteerQueuePolicy policy) {
        return executor(sessions, loop, signals, clock, policy, RecoveryFaultInjector.NONE);
    }

    private static SessionExecutor executor(
            MemorySessions sessions,
            AgentLoop loop,
            CancellationSignalStore signals,
            Clock clock,
            SteerQueuePolicy policy,
            RecoveryFaultInjector faultInjector) {
        return new SessionExecutor(
                authorityStore(),
                sessions,
                loop,
                Executors.newSingleThreadScheduledExecutor(),
                clock,
                signals,
                Duration.ZERO,
                Duration.ofSeconds(1),
                null,
                faultInjector,
                policy);
    }

    private static ExecutionAuthorityStore authorityStore() {
        return new ExecutionAuthorityStore() {
            private final AtomicInteger tokens = new AtomicInteger(20);

            @Override
            public Optional<ExecutionAuthority> acquire(
                    String sessionId, String ownerToken, long ttlMillis) {
                return Optional.of(authority(tokens.incrementAndGet()));
            }

            @Override
            public boolean isHeld(String sessionId) {
                return false;
            }
        };
    }

    private static ExecutionAuthority authority(long token) {
        return new ExecutionAuthority() {
            @Override public long fencingToken() { return token; }
            @Override public boolean renew(long ttlMillis) { return true; }
            @Override public void close() { }
        };
    }

    private static AgentLoop immediateLoop() {
        return loop((session, context, publisher, token) ->
                outcome(context, 1, AgentTerminalState.RESULTS_READY, null));
    }

    private static AgentLoop loop(TestLoop delegate) {
        return new AgentLoop() {
            @Override public String loopType() { return "test"; }
            @Override
            public AgentRunResult run(
                    AgentSession session,
                    RuntimeContext context,
                    PushEventPublisher publisher,
                    CancellationToken token) {
                return delegate.run(session, context, publisher, token);
            }
        };
    }

    private static PushEventPublisher publisher(List<PushEvent> events) {
        return event -> {
            events.add(event);
            return events.size() - 1L;
        };
    }

    private static RuntimeContext context(AgentRunRequest request) {
        return context(request, Map.of("persistent", "yes", "modelOverride", "forbidden"));
    }

    private static RuntimeContext context(
            AgentRunRequest request,
            Map<String, Object> features) {
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop", "prompt", "provider",
                Set.of("tool"), 2, 1, Duration.ofSeconds(1), true);
        LlmClient llm = new LlmClient() {
            @Override public String version() { return "test"; }
            @Override
            public io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision chat(
                    io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext context) {
                return new io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision.Complete(Map.of());
            }
        };
        return new RuntimeContext(
                definition,
                request,
                llm,
                features);
    }

    private static AgentRunRequest currentRequest() {
        return new AgentRunRequest(
                "request-1", "session", "turn-1", "current", Map.of());
    }

    private static AgentRunRequest queuedRequest(
            String requestId,
            String turnId,
            String input,
            Map<String, Object> attributes) {
        return new AgentRunRequest(
                requestId,
                "session",
                turnId,
                input,
                attributes,
                new SessionStatePatch(0, Map.of("query", input)),
                AgentIngressMode.STEER);
    }

    private static AgentRunResult outcome(
            RuntimeContext context,
            int call,
            AgentTerminalState state,
            CancellationCause cause) {
        AgentRunTrace.DefinitionSnapshot snapshot = new AgentRunTrace.DefinitionSnapshot(
                "agent", "v1", "loop", "prompt", "provider",
                2, 1, 1000, Map.of("tool", "v1"));
        AgentRunTrace trace = new AgentRunTrace(
                String.format("00000000-0000-0000-0000-%012d", call),
                context.request().requestId(),
                context.request().sessionId(),
                context.request().turnId(),
                snapshot,
                Instant.parse("2026-09-15T00:00:00Z"),
                1,
                state,
                "AGENT",
                null,
                cause == null ? null : cause.name(),
                io.seekflux.platform.agentruntime.domain.model.run.LlmUsage.UNMEASURED,
                List.of());
        return new AgentRunResult(
                state,
                Map.of(),
                null,
                null,
                cause == null ? null : cause.name(),
                false,
                trace);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static String messageId(AgentRunRequest request) {
        return UUID.nameUUIDFromBytes(
                (request.sessionId() + ":" + request.requestId() + ":"
                        + request.turnId() + ":user").getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static final class MemorySessions implements AgentSessionStore {
        private final List<WorkspaceEvent> events = new ArrayList<>();
        private final List<String> ordering;
        private final List<String> cancellationReasons = new ArrayList<>();

        private MemorySessions(List<String> ordering) {
            this.ordering = ordering;
        }

        static MemorySessions running(AgentRunRequest request, List<String> ordering) {
            MemorySessions store = new MemorySessions(ordering);
            Instant now = Instant.parse("2026-09-15T00:00:00Z");
            store.events.add(new WorkspaceEvent.SessionCreated(1, now, "agent", "v1"));
            store.events.add(new WorkspaceEvent.UserMessage(
                    2, now, 1, messageId(request),
                    request.requestId(), request.turnId(), request.input()));
            return store;
        }

        static MemorySessions promoted(AgentRunRequest request) {
            MemorySessions store = new MemorySessions(new ArrayList<>());
            Instant now = Instant.parse("2026-09-15T00:00:00Z");
            String messageId = messageId(request);
            store.events.add(new WorkspaceEvent.SessionCreated(1, now, "agent", "v1"));
            store.events.add(new WorkspaceEvent.QueuedUserMessage(
                    2, now.plusMillis(1), 1, messageId, request));
            store.events.add(new WorkspaceEvent.UserMessage(
                    3, now.plusMillis(2), 1, messageId,
                    request.requestId(), request.turnId(), request.input()));
            return store;
        }

        static MemorySessions completed(AgentRunRequest request, List<String> ordering) {
            MemorySessions store = running(request, ordering);
            Instant now = Instant.parse("2026-09-15T00:00:00Z");
            store.events.add(new WorkspaceEvent.RunCompleted(
                    3,
                    now.plusMillis(1),
                    "00000000-0000-0000-0000-000000000001",
                    AgentTerminalState.RESULTS_READY,
                    null));
            return store;
        }

        @Override
        public synchronized Optional<AgentSession> restoreFresh(String sessionId) {
            return Optional.of(AgentSession.replay(sessionId, List.copyOf(events)));
        }

        @Override
        public AgentSession createIfAbsent(
                String sessionId, AgentDefinition definition, Instant eventTime) {
            return restoreFresh(sessionId).orElseThrow();
        }

        @Override
        public IngressCommitResult commitIngress(
                AgentRunRequest request, long fencingToken, Instant eventTime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public synchronized QueueCommitResult enqueue(
                AgentRunRequest request, int maxQueueDepth, Instant eventTime) {
            return enqueue(request, Map.of(), maxQueueDepth, eventTime);
        }

        @Override
        public synchronized QueueCommitResult enqueue(
                AgentRunRequest request,
                Map<String, Object> persistentFeatures,
                int maxQueueDepth,
                Instant eventTime) {
            ordering.add("enqueue");
            AgentSession session = AgentSession.replay("session", List.copyOf(events));
            boolean consumed = events.stream().anyMatch(event ->
                    event instanceof WorkspaceEvent.UserMessage user
                            && user.requestId().equals(request.requestId()));
            if (consumed) {
                return QueueCommitResult.duplicateConsumed(session.queuedMessages().size());
            }
            boolean pendingDuplicate = events.stream().anyMatch(event ->
                    event instanceof WorkspaceEvent.QueuedUserMessage queued
                            && queued.request().requestId().equals(request.requestId()));
            if (pendingDuplicate) {
                return QueueCommitResult.duplicatePending(session.queuedMessages().size());
            }
            int depth = session.queuedMessages().size();
            if (depth >= maxQueueDepth) {
                return QueueCommitResult.full(depth);
            }
            events.add(new WorkspaceEvent.QueuedUserMessage(
                    events.size() + 1L,
                    eventTime,
                    1,
                    messageId(request),
                    request,
                    persistentFeatures));
            return QueueCommitResult.committed(depth + 1);
        }

        @Override
        public synchronized Optional<QueuedMessageBatch> promoteQueued(
                String sessionId, long fencingToken, Instant eventTime) {
            AgentSession session = AgentSession.replay(sessionId, List.copyOf(events));
            List<WorkspaceEvent.QueuedUserMessage> queued = session.queuedMessages();
            if (queued.isEmpty()) {
                return Optional.empty();
            }
            SessionStatePatch patch = queued.getLast().request().statePatch();
            if (patch != null) {
                events.add(new WorkspaceEvent.StatePatched(
                        events.size() + 1L,
                        eventTime,
                        session.stateVersion(),
                        session.stateVersion() + 1,
                        patch.state()));
            }
            for (WorkspaceEvent.QueuedUserMessage message : queued) {
                AgentRunRequest request = message.request();
                events.add(new WorkspaceEvent.UserMessage(
                        events.size() + 1L,
                        eventTime,
                        1,
                        message.messageId(),
                        request.requestId(),
                        request.turnId(),
                        request.input()));
            }
            return Optional.of(new QueuedMessageBatch(queued));
        }

        @Override
        public synchronized void appendOutcome(
                String sessionId,
                AgentRunResult result,
                long fencingToken,
                Instant eventTime) {
            if (result.state() == AgentTerminalState.CANCELLED) {
                cancellationReasons.add(result.cancellationReason());
                events.add(new WorkspaceEvent.RunCancelled(
                        events.size() + 1L,
                        eventTime,
                        result.trace().agentRunId(),
                        result.cancellationReason()));
            } else if (result.state() == AgentTerminalState.FAILED) {
                events.add(new WorkspaceEvent.RunFailed(
                        events.size() + 1L,
                        eventTime,
                        result.trace().agentRunId(),
                        "FAILED"));
            } else {
                events.add(new WorkspaceEvent.RunCompleted(
                        events.size() + 1L,
                        eventTime,
                        result.trace().agentRunId(),
                        result.state(),
                        result.fallbackReason()));
            }
        }

        synchronized List<String> cancellationReasons() {
            return List.copyOf(cancellationReasons);
        }
    }

    private static final class MemorySignals implements CancellationSignalStore {
        private final List<String> ordering;
        private Signal signal;

        private MemorySignals(List<String> ordering) {
            this.ordering = ordering;
        }

        @Override
        public synchronized CancelSignal poll(String sessionId, Instant taskStartedAt) {
            if (signal == null || !signal.time().isAfter(taskStartedAt)) {
                return CancelSignal.NONE;
            }
            return new CancelSignal(true, signal.cause());
        }

        @Override
        public boolean write(String sessionId, boolean steer, Instant signalTime) {
            return write(
                    sessionId,
                    steer ? CancellationCause.STEER : CancellationCause.USER_CANCEL,
                    signalTime);
        }

        @Override
        public synchronized boolean write(
                String sessionId, CancellationCause cause, Instant signalTime) {
            ordering.add("signal");
            signal = new Signal(signalTime, cause);
            return true;
        }

        @Override
        public synchronized boolean clearSteerThrough(String sessionId, Instant signalTime) {
            if (signal != null
                    && signal.cause() == CancellationCause.STEER
                    && !signal.time().isAfter(signalTime)) {
                signal = null;
                return true;
            }
            return false;
        }

        private record Signal(Instant time, CancellationCause cause) {
        }
    }

    private static final class TickingClock extends Clock {
        private final AtomicReference<Instant> now =
                new AtomicReference<>(Instant.parse("2026-09-15T00:00:00Z"));

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.getAndUpdate(value -> value.plusMillis(1)); }
    }

    @FunctionalInterface
    private interface TestLoop {
        AgentRunResult run(
                AgentSession session,
                RuntimeContext context,
                PushEventPublisher publisher,
                CancellationToken token);
    }
}
