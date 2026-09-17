package io.seekflux.apps.agentserver.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.seekflux.agent.port.in.AgentSearchUseCase;
import io.seekflux.platform.agentruntime.application.api.Router;
import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventStream;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushFrame;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentSearchControllerStreamTest {

    @Test
    void reconnectWithLastEventIdNeverStartsADuplicateExecution() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AgentSearchUseCase useCase = command -> {
            executions.incrementAndGet();
            throw new AssertionError("reconnect must not execute the command");
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            AgentSearchController controller = new AgentSearchController(
                    useCase, mock(Router.class), new TerminalStream(), executor, 1_000);
            AgentSearchRequest request = new AgentSearchRequest(
                    "request-1", "session-1", "turn-1", "search-assistant", "露营",
                    0, 12, List.of(), null, null, null, null);

            controller.stream(request, "7");
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executions.get()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void cancelResolvesAPersistedWaitInsteadOfWritingAnUnconsumedSignal() {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        WaitState wait = new WaitState.AsyncTask(
                1, "wait-1", "session-1", "request-1", "turn-1", "checkpoint-1",
                "call-1", now, now.plusSeconds(30), "task-1", "webhook");
        AgentMessage.Assistant assistant = new AgentMessage.Assistant(
                1, "assistant-1", "request-1", "turn-1", "run-1", 1,
                "working", null, false,
                List.of(new AgentMessage.ToolCall("call-1", "async", 0, Map.of())));
        AgentSession session = AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "agent", "v1"),
                new WorkspaceEvent.UserMessage(2, now, "request-1", "turn-1", "work"),
                new WorkspaceEvent.AssistantMessage(3, now, assistant),
                new WorkspaceEvent.WaitSuspended(4, now, wait)));
        Router router = mock(Router.class);
        AtomicReference<WaitResolution> resolution = new AtomicReference<>();
        AgentSearchController controller = new AgentSearchController(
                command -> null,
                router,
                null,
                null,
                1_000,
                (value, publisher) -> {
                    resolution.set(value);
                    return RouterResult.completed(null);
                },
                sessions(session),
                Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC));

        Map<String, Object> response = controller.cancel("session-1");

        assertThat(response.get("cancelled")).isEqualTo(true);
        assertThat(resolution.get().outcome()).isEqualTo(WaitResolution.Outcome.CANCELLED);
        assertThat(resolution.get().errorCode()).isEqualTo("USER_CANCEL");
        verifyNoInteractions(router);
    }

    private static AgentSessionStore sessions(AgentSession session) {
        return new AgentSessionStore() {
            @Override public Optional<AgentSession> restoreFresh(String sessionId) {
                return Optional.of(session);
            }

            @Override public AgentSession createIfAbsent(
                    String sessionId, AgentDefinition definition, Instant eventTime) {
                return session;
            }

            @Override public IngressCommitResult commitIngress(
                    AgentRunRequest request, long fencingToken, Instant eventTime) {
                return IngressCommitResult.COMMITTED;
            }

            @Override public void appendOutcome(
                    String sessionId,
                    AgentRunResult result,
                    long fencingToken,
                    Instant eventTime) {
            }
        };
    }

    private static final class TerminalStream implements PushEventStream {
        @Override
        public PushEventPublisher publisher(String sessionId, String requestId) {
            return PushEventPublisher.NOOP;
        }

        @Override
        public Subscription subscribe(String sessionId, long afterSequence) {
            return new Subscription() {
                @Override
                public PushFrame poll(Duration timeout) {
                    return null;
                }

                @Override
                public boolean replayGap() {
                    return false;
                }

                @Override
                public boolean overflowed() {
                    return false;
                }

                @Override
                public boolean terminalObserved() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
