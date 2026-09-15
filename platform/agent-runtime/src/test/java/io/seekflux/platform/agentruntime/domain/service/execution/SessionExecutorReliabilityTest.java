package io.seekflux.platform.agentruntime.domain.service.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunTrace;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.exception.AgentExecutionFencedException;
import io.seekflux.platform.agentruntime.domain.exception.UnsafeToolRecoveryException;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthority;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.domain.service.loop.AgentLoop;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.recovery.CheckpointBoundary;
import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeAction;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeIngress;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolJournalStatus;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SessionExecutorReliabilityTest {

    @Test
    void staleOwnerCannotAppendOutcomeAfterLosingAuthority() {
        AtomicBoolean outcomeAppended = new AtomicBoolean();
        FakeSessions sessions = new FakeSessions(outcomeAppended);
        AtomicInteger renewals = new AtomicInteger();
        ExecutionAuthority authority = new ExecutionAuthority() {
            @Override public long fencingToken() { return 7; }
            @Override public boolean renew(long ttlMillis) { return renewals.incrementAndGet() == 1; }
            @Override public void close() { }
        };
        SessionExecutor executor = executor(sessions, immediateLoop(), CancellationSignalStore.NOOP);
        try {
            assertThrows(AgentExecutionFencedException.class,
                    () -> executor.run("session", runtimeContext(), PushEventPublisher.NOOP, authority));
            assertFalse(outcomeAppended.get());
        } finally {
            executor.close();
        }
    }

    @Test
    void cancellationWrittenByAnotherInstanceStopsTheRunningLoop() throws Exception {
        SharedSignals signals = new SharedSignals();
        FakeSessions sessions = new FakeSessions(new AtomicBoolean());
        SessionExecutor owner = executor(sessions, waitingLoop(), signals);
        SessionExecutor remote = executor(sessions, immediateLoop(), signals);
        ExecutionAuthority authority = authority(11);
        try {
            CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(
                    () -> owner.run("session", runtimeContext(), PushEventPublisher.NOOP, authority));
            while (!signals.loopStarted.get()) {
                Thread.onSpinWait();
            }
            assertTrue(remote.cancel("session", false));
            AgentRunResult result = running.get();
            assertTrue(result.state() == AgentTerminalState.CANCELLED);
            assertEquals("USER_CANCEL", result.cancellationReason());
        } finally {
            owner.close();
            remote.close();
        }
    }

    @Test
    void shutdownCancelsActiveLoopWithAStableCause() throws Exception {
        SharedSignals signals = new SharedSignals();
        FakeSessions sessions = new FakeSessions(new AtomicBoolean());
        SessionExecutor owner = executor(sessions, waitingLoop(), signals);
        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(
                () -> owner.run("session", runtimeContext(), PushEventPublisher.NOOP, authority(12)));
        while (!SharedSignals.loopStarted.get()) {
            Thread.onSpinWait();
        }

        owner.close();
        AgentRunResult result = running.get();

        assertEquals(AgentTerminalState.CANCELLED, result.state());
        assertEquals("SHUTDOWN", result.cancellationReason());
    }

    @Test
    void terminalCheckpointCommitsOutcomeWithoutDispatchingLoopAgain() {
        AtomicBoolean outcomeAppended = new AtomicBoolean();
        AtomicBoolean loopCalled = new AtomicBoolean();
        FakeSessions sessions = new FakeSessions(outcomeAppended);
        RuntimeContext context = runtimeContext();
        AgentRunResult terminal = outcome(context, AgentTerminalState.RESULTS_READY);
        RuntimeCheckpoint checkpoint = terminalCheckpoint(terminal);
        AgentRecoveryStore recovery = new AgentRecoveryStore() {
            @Override public boolean enabled() { return true; }
            @Override public RecoveryPlan commitResume(ResumeIngress ingress, long token, Instant time) {
                return new RecoveryPlan(ResumeAction.COMMIT_TERMINAL, checkpoint, List.of());
            }
        };
        AgentLoop loop = new AgentLoop() {
            @Override public String loopType() { return "test"; }
            @Override public AgentRunResult run(AgentSession session, RuntimeContext runtime,
                    PushEventPublisher publisher, CancellationToken token) {
                loopCalled.set(true);
                return terminal;
            }
        };
        SessionExecutor executor = executor(sessions, loop, CancellationSignalStore.NOOP, recovery);
        try {
            AgentRunResult result = executor.run(
                    "session", context, PushEventPublisher.NOOP, authority(20),
                    IngressCommitResult.RECOVERED);

            assertEquals(terminal, result);
            assertTrue(outcomeAppended.get());
            assertFalse(loopCalled.get());
        } finally {
            executor.close();
        }
    }

    @Test
    void unknownMutatingToolFailsClosedBeforeLoopDispatch() {
        AtomicBoolean outcomeAppended = new AtomicBoolean();
        AtomicBoolean loopCalled = new AtomicBoolean();
        FakeSessions sessions = new FakeSessions(outcomeAppended);
        RuntimeCheckpoint checkpoint = preTurnCheckpoint();
        ToolCallJournalEntry unsafe = unsafeUnknownTool(checkpoint);
        AgentRecoveryStore recovery = new AgentRecoveryStore() {
            @Override public boolean enabled() { return true; }
            @Override public RecoveryPlan commitResume(ResumeIngress ingress, long token, Instant time) {
                return new RecoveryPlan(
                        ResumeAction.FAIL_UNSAFE_PENDING_TOOL, checkpoint, List.of(unsafe));
            }
        };
        AgentLoop loop = new AgentLoop() {
            @Override public String loopType() { return "test"; }
            @Override public AgentRunResult run(AgentSession session, RuntimeContext runtime,
                    PushEventPublisher publisher, CancellationToken token) {
                loopCalled.set(true);
                return outcome(runtime, AgentTerminalState.RESULTS_READY);
            }
        };
        SessionExecutor executor = executor(sessions, loop, CancellationSignalStore.NOOP, recovery);
        try {
            assertThrows(UnsafeToolRecoveryException.class, () -> executor.run(
                    "session", runtimeContext(), PushEventPublisher.NOOP, authority(21),
                    IngressCommitResult.RECOVERED));
            assertFalse(loopCalled.get());
            assertFalse(outcomeAppended.get());
        } finally {
            executor.close();
        }
    }

    private static SessionExecutor executor(
            FakeSessions sessions, AgentLoop loop, CancellationSignalStore signals) {
        return executor(sessions, loop, signals, AgentRecoveryStore.NOOP);
    }

    private static SessionExecutor executor(
            FakeSessions sessions,
            AgentLoop loop,
            CancellationSignalStore signals,
            AgentRecoveryStore recovery) {
        return new SessionExecutor(
                new ExecutionAuthorityStore() {
                    @Override public Optional<ExecutionAuthority> acquire(
                            String sessionId, String ownerToken, long ttlMillis) {
                        return Optional.of(authority(1));
                    }

                    @Override public boolean isHeld(String sessionId) { return true; }
                },
                sessions,
                loop,
                Executors.newSingleThreadScheduledExecutor(),
                Clock.fixed(Instant.parse("2026-08-10T00:00:00Z"), ZoneOffset.UTC),
                signals,
                Duration.ZERO,
                Duration.ofSeconds(1),
                recovery,
                RecoveryFaultInjector.NONE);
    }

    private static ExecutionAuthority authority(long token) {
        return new ExecutionAuthority() {
            @Override public long fencingToken() { return token; }
            @Override public boolean renew(long ttlMillis) { return true; }
            @Override public void close() { }
        };
    }

    private static AgentLoop immediateLoop() {
        return new AgentLoop() {
            @Override public String loopType() { return "test"; }
            @Override public AgentRunResult run(AgentSession session, RuntimeContext context,
                    PushEventPublisher publisher, CancellationToken token) {
                return outcome(context, AgentTerminalState.RESULTS_READY);
            }
        };
    }

    private static AgentLoop waitingLoop() {
        return new AgentLoop() {
            @Override public String loopType() { return "test"; }
            @Override public AgentRunResult run(AgentSession session, RuntimeContext context,
                    PushEventPublisher publisher, CancellationToken token) {
                SharedSignals.started(token);
                long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
                while (!token.isCancelled() && System.nanoTime() < deadline) {
                    Thread.onSpinWait();
                }
                return outcome(context, token.isCancelled()
                        ? AgentTerminalState.CANCELLED
                        : AgentTerminalState.FAILED, token.cause());
            }
        };
    }

    private static RuntimeContext runtimeContext() {
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop", "prompt", "provider",
                Set.of("tool"), 2, 1, Duration.ofSeconds(1), true);
        AgentRunRequest request = new AgentRunRequest(
                "request", "session", "turn", "input", Map.of());
        LlmClient llm = new LlmClient() {
            @Override public String version() { return "test"; }
            @Override public io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision chat(
                    io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext context) {
                return new io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision.Complete(Map.of());
            }
        };
        return new RuntimeContext(definition, request, llm, Map.of());
    }

    private static RuntimeCheckpoint terminalCheckpoint(AgentRunResult terminal) {
        return new RuntimeCheckpoint(
                1, "00000000-0000-0000-0000-000000000010", CheckpointBoundary.COMPLETED,
                "session", "request", terminal.trace().agentRunId(), "turn", 20, 1,
                terminal.trace().definition(), 1, 0, 900, Map.of(), List.of(), List.of(),
                Set.of(), io.seekflux.platform.agentruntime.domain.model.run.LlmUsage.UNMEASURED,
                List.of(), terminal, Instant.parse("2026-09-14T00:00:00Z"));
    }

    private static RuntimeCheckpoint preTurnCheckpoint() {
        AgentRunResult terminal = outcome(runtimeContext(), AgentTerminalState.RESULTS_READY);
        return new RuntimeCheckpoint(
                1, "00000000-0000-0000-0000-000000000011", CheckpointBoundary.PRE_TURN,
                "session", "request", terminal.trace().agentRunId(), "turn", 21, 1,
                terminal.trace().definition(), 1, 0, 900, Map.of(), List.of(), List.of(),
                Set.of(), io.seekflux.platform.agentruntime.domain.model.run.LlmUsage.UNMEASURED,
                List.of(), null, Instant.parse("2026-09-14T00:00:00Z"));
    }

    private static ToolCallJournalEntry unsafeUnknownTool(RuntimeCheckpoint checkpoint) {
        AgentMessage.Assistant assistant = new AgentMessage.Assistant(
                1, "assistant", "request", "turn", checkpoint.attemptId(), 1,
                "publish", null, false,
                List.of(new AgentMessage.ToolCall("call", "publish", 0, Map.of())));
        return new ToolCallJournalEntry(
                1, "session", "request", "turn", checkpoint.attemptId(), 1, 0,
                "call", "publish", "publish-v1", AgentTool.Effect.MUTATING,
                ToolJournalStatus.UNKNOWN, Map.of(), "digest", false,
                assistant, null, Instant.parse("2026-09-14T00:00:00Z"));
    }

    private static AgentRunResult outcome(RuntimeContext context, AgentTerminalState state) {
        return outcome(context, state, null);
    }

    private static AgentRunResult outcome(
            RuntimeContext context,
            AgentTerminalState state,
            CancellationCause cancellationCause) {
        String cancellationReason = cancellationCause == null ? null : cancellationCause.name();
        AgentRunTrace trace = new AgentRunTrace(
                "00000000-0000-0000-0000-000000000001",
                context.request().requestId(), "session", "turn",
                new AgentRunTrace.DefinitionSnapshot(
                        "agent", "v1", "loop", "prompt", "provider", 2, 1, 1000,
                        Map.of("tool", "v1")),
                Instant.now(), 1, state, "AGENT", null, cancellationReason,
                io.seekflux.platform.agentruntime.domain.model.run.LlmUsage.UNMEASURED, List.of());
        return new AgentRunResult(
                state, Map.of(), null, null, cancellationReason, false, trace);
    }

    private static final class FakeSessions implements AgentSessionStore {
        private final AtomicBoolean outcomeAppended;
        private final AgentSession session = AgentSession.replay("session", List.of(
                new WorkspaceEvent.SessionCreated(1, Instant.now(), "agent", "v1")));

        private FakeSessions(AtomicBoolean outcomeAppended) {
            this.outcomeAppended = outcomeAppended;
        }

        @Override public Optional<AgentSession> restoreFresh(String sessionId) { return Optional.of(session); }
        @Override public AgentSession createIfAbsent(String sessionId, AgentDefinition definition, Instant time) { return session; }
        @Override public IngressCommitResult commitIngress(AgentRunRequest request, long token, Instant time) { return IngressCommitResult.COMMITTED; }
        @Override public void appendOutcome(String sessionId, AgentRunResult result, long token, Instant time) { outcomeAppended.set(true); }
    }

    private static final class SharedSignals implements CancellationSignalStore {
        private static final AtomicBoolean loopStarted = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        private SharedSignals() {
            loopStarted.set(false);
        }

        private static void started(CancellationToken ignored) {
            loopStarted.set(true);
        }

        @Override public CancelSignal poll(String sessionId, Instant startedAt) {
            return cancelled.get() ? new CancelSignal(true, false) : CancelSignal.NONE;
        }

        @Override public boolean write(String sessionId, boolean steer, Instant time) {
            cancelled.set(true);
            return true;
        }
    }
}
