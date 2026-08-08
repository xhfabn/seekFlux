package io.seekflux.platform.agentruntime.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.AgentRunResult;
import io.seekflux.platform.agentruntime.AgentRunTrace;
import io.seekflux.platform.agentruntime.AgentTerminalState;
import io.seekflux.platform.agentruntime.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.execution.CancellationToken;
import io.seekflux.platform.agentruntime.execution.ExecutionAuthority;
import io.seekflux.platform.agentruntime.execution.ExecutionAuthorityStore;
import io.seekflux.platform.agentruntime.execution.SessionExecutor;
import io.seekflux.platform.agentruntime.feature.DefaultFeaturePipeline;
import io.seekflux.platform.agentruntime.feature.FeatureNode;
import io.seekflux.platform.agentruntime.feature.FeatureRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class DefaultRouterTest {

    @Test
    void acquiresPositionBeforeCommittingIngress() {
        List<String> calls = new ArrayList<>();
        FakeSessionStore sessions = new FakeSessionStore(calls, IngressCommitResult.COMMITTED);
        ExecutionAuthorityStore authorities = authorityStore(calls, true);
        SessionExecutor executor = executor(authorities, sessions, calls);
        try {
            RouterResult result = router(sessions, executor).execute(request(), PushEventPublisher.NOOP);

            assertEquals(RouterResult.Status.COMPLETED, result.status());
            assertTrue(calls.indexOf("acquire") < calls.indexOf("commit"));
            assertTrue(calls.contains("close"));
        } finally {
            executor.close();
        }
    }

    @Test
    void duplicateRequestDoesNotEnterAgentLoop() {
        List<String> calls = new ArrayList<>();
        FakeSessionStore sessions = new FakeSessionStore(calls, IngressCommitResult.DUPLICATE);
        SessionExecutor executor = executor(authorityStore(calls, true), sessions, calls);
        try {
            RouterResult result = router(sessions, executor).execute(request(), PushEventPublisher.NOOP);

            assertEquals(RouterResult.Status.DUPLICATE, result.status());
            assertFalse(calls.contains("loop"));
            assertTrue(calls.contains("close"));
        } finally {
            executor.close();
        }
    }

    @Test
    void busySessionDoesNotCommitMessage() {
        List<String> calls = new ArrayList<>();
        FakeSessionStore sessions = new FakeSessionStore(calls, IngressCommitResult.COMMITTED);
        SessionExecutor executor = executor(authorityStore(calls, false), sessions, calls);
        try {
            RouterResult result = router(sessions, executor).execute(request(), PushEventPublisher.NOOP);

            assertEquals(RouterResult.Status.BUSY, result.status());
            assertFalse(calls.contains("commit"));
        } finally {
            executor.close();
        }
    }

    private static DefaultRouter router(FakeSessionStore sessions, SessionExecutor executor) {
        FeatureNode init = new FeatureNode() {
            @Override public String name() { return "init"; }
            @Override public int order() { return 100; }
            @Override public void process(io.seekflux.platform.agentruntime.feature.FeatureContext context) {
                context.session(sessions.session);
                context.runtimeContext(new RuntimeContext(
                        context.request().definition(),
                        context.request().runRequest(),
                        context.request().llmClient(),
                        Map.of()));
            }
        };
        return new DefaultRouter(
                new DefaultFeaturePipeline(List.of(init)),
                sessions,
                executor,
                Clock.systemUTC());
    }

    private static SessionExecutor executor(
            ExecutionAuthorityStore authorities,
            FakeSessionStore sessions,
            List<String> calls) {
        AgentLoop loop = new AgentLoop() {
            @Override public String loopType() { return "test"; }
            @Override
            public AgentRunResult run(
                    AgentSession session,
                    RuntimeContext context,
                    PushEventPublisher publisher,
                    CancellationToken cancellationToken) {
                calls.add("loop");
                return outcome(context);
            }
        };
        return new SessionExecutor(
                authorities,
                sessions,
                loop,
                Executors.newSingleThreadScheduledExecutor(),
                Clock.systemUTC());
    }

    private static ExecutionAuthorityStore authorityStore(List<String> calls, boolean acquired) {
        return new ExecutionAuthorityStore() {
            @Override
            public Optional<ExecutionAuthority> acquire(String sessionId, String ownerToken, long ttlMillis) {
                calls.add("acquire");
                if (!acquired) {
                    return Optional.empty();
                }
                return Optional.of(new ExecutionAuthority() {
                    @Override public boolean renew(long ttl) { calls.add("renew"); return true; }
                    @Override public void close() {
                        calls.add("close");
                    }
                });
            }

            @Override public boolean isHeld(String sessionId) { return acquired; }
        };
    }

    private static FeatureRequest request() {
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop", "prompt", "decision",
                Set.of("tool"), 2, 1, Duration.ofSeconds(1), true);
        AgentRunRequest run = new AgentRunRequest("request", "session", "turn", "input", Map.of());
        LlmClient client = new LlmClient() {
            @Override public String version() { return "test"; }
            @Override public AgentDecision chat(io.seekflux.platform.agentruntime.context.AssembledContext context) {
                return new AgentDecision.Complete(Map.of());
            }
        };
        return new FeatureRequest(definition, run, client);
    }

    private static AgentRunResult outcome(RuntimeContext context) {
        AgentRunTrace.DefinitionSnapshot snapshot = new AgentRunTrace.DefinitionSnapshot(
                context.definition().id(), "v1", "loop", "prompt", "decision",
                2, 1, 1000, Map.of("tool", "v1"));
        AgentRunTrace trace = new AgentRunTrace(
                "00000000-0000-0000-0000-000000000001",
                context.request().requestId(),
                context.request().sessionId(),
                context.request().turnId(),
                snapshot,
                Instant.now(),
                1,
                AgentTerminalState.RESULTS_READY,
                "AGENT",
                null,
                List.of());
        return new AgentRunResult(AgentTerminalState.RESULTS_READY, Map.of(), null, null, false, trace);
    }

    private static final class FakeSessionStore implements AgentSessionStore {
        private final List<String> calls;
        private final IngressCommitResult commitResult;
        private final AgentSession session = AgentSession.replay("session", List.of(
                new WorkspaceEvent.SessionCreated(1, Instant.now(), "agent", "v1")));

        private FakeSessionStore(List<String> calls, IngressCommitResult commitResult) {
            this.calls = calls;
            this.commitResult = commitResult;
        }

        @Override public Optional<AgentSession> restoreFresh(String sessionId) { calls.add("restore"); return Optional.of(session); }
        @Override public AgentSession createIfAbsent(String sessionId, AgentDefinition definition, Instant time) { return session; }
        @Override public IngressCommitResult commitIngress(AgentRunRequest request, Instant time) { calls.add("commit"); return commitResult; }
        @Override public void appendOutcome(String sessionId, AgentRunResult result, Instant time) { calls.add("outcome"); }
    }
}
