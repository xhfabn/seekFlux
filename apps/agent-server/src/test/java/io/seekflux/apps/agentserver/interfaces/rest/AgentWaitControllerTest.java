package io.seekflux.apps.agentserver.interfaces.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentWaitControllerTest {

    @Test
    void dispatchesAnIdempotentApprovalForThePendingWait() {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        WaitState wait = wait(now);
        AgentSession session = waitingSession(wait, now);
        AtomicReference<WaitResolution> captured = new AtomicReference<>();
        AgentWaitController controller = new AgentWaitController(
                (resolution, publisher) -> {
                    captured.set(resolution);
                    return RouterResult.duplicate();
                },
                sessions(session),
                Clock.fixed(now.plusSeconds(1), ZoneOffset.UTC));

        AgentWaitController.AgentWaitResponse response = controller.resolve(
                "user-1",
                "session-1",
                wait.waitId(),
                new AgentWaitController.AgentWaitResolutionRequest(
                        "resolution-1",
                        "request-1",
                        "turn-1",
                        WaitResolution.Outcome.APPROVED,
                        Map.of(),
                        null));

        assertEquals("DUPLICATE", response.status());
        assertEquals(WaitResolution.Outcome.APPROVED, captured.get().outcome());
        assertEquals("user-1", captured.get().resolvedBy());
        assertEquals(now.plusSeconds(1), captured.get().resolvedAt());
    }

    private static WaitState wait(Instant now) {
        return new WaitState.Hitl(
                1,
                "00000000-0000-0000-0000-000000000020",
                "session-1",
                "request-1",
                "turn-1",
                "00000000-0000-0000-0000-000000000010",
                "call-1",
                now,
                now.plusSeconds(30),
                "approve publish",
                "publish",
                Map.of("value", "ok"));
    }

    private static AgentSession waitingSession(WaitState wait, Instant now) {
        AgentMessage.Assistant assistant = new AgentMessage.Assistant(
                1, "assistant-1", "request-1", "turn-1", "run-1", 1,
                "publish", null, false,
                List.of(new AgentMessage.ToolCall(
                        "call-1", "publish", 0, Map.of("value", "ok"))));
        return AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "agent", "v1"),
                new WorkspaceEvent.UserMessage(2, now, "request-1", "turn-1", "publish"),
                new WorkspaceEvent.AssistantMessage(3, now, assistant),
                new WorkspaceEvent.WaitSuspended(4, now, wait)));
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
}
