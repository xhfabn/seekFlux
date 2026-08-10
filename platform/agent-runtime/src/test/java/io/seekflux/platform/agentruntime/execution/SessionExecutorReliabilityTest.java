package io.seekflux.platform.agentruntime.execution;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.AgentRunResult;
import io.seekflux.platform.agentruntime.AgentRunTrace;
import io.seekflux.platform.agentruntime.AgentTerminalState;
import io.seekflux.platform.agentruntime.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import io.seekflux.platform.agentruntime.loop.AgentLoop;
import io.seekflux.platform.agentruntime.session.AgentSession;
import io.seekflux.platform.agentruntime.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.session.IngressCommitResult;
import io.seekflux.platform.agentruntime.session.WorkspaceEvent;
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
        } finally {
            owner.close();
            remote.close();
        }
    }

    private static SessionExecutor executor(
            FakeSessions sessions, AgentLoop loop, CancellationSignalStore signals) {
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
                Duration.ofSeconds(1));
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
                        : AgentTerminalState.FAILED);
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
            @Override public io.seekflux.platform.agentruntime.AgentDecision chat(
                    io.seekflux.platform.agentruntime.context.AssembledContext context) {
                return new io.seekflux.platform.agentruntime.AgentDecision.Complete(Map.of());
            }
        };
        return new RuntimeContext(definition, request, llm, Map.of());
    }

    private static AgentRunResult outcome(RuntimeContext context, AgentTerminalState state) {
        AgentRunTrace trace = new AgentRunTrace(
                "00000000-0000-0000-0000-000000000001",
                context.request().requestId(), "session", "turn",
                new AgentRunTrace.DefinitionSnapshot(
                        "agent", "v1", "loop", "prompt", "provider", 2, 1, 1000,
                        Map.of("tool", "v1")),
                Instant.now(), 1, state, "AGENT", null, List.of());
        return new AgentRunResult(state, Map.of(), null, null, false, trace);
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
