package io.seekflux.platform.agentruntime.domain.model.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentSessionTest {

    @Test
    void replaysVersionedWorkspaceStateSeparatelyFromExecutionStatus() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");
        AgentSession session = AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "search-assistant", "v2"),
                new WorkspaceEvent.StatePatched(2, now, 0, 1, Map.of("type", "search_goal_v1")),
                new WorkspaceEvent.UserMessage(3, now, "request-1", "turn-1", "杭州露营")));

        assertEquals(1, session.stateVersion());
        assertEquals("search_goal_v1", session.workspaceState().get("type"));
        assertEquals(AgentSessionStatus.EXECUTING, session.status());
        assertEquals(3, session.position());
    }

    @Test
    void rejectsNonContiguousWorkspaceStateVersions() {
        Instant now = Instant.parse("2026-08-08T00:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "search-assistant", "v2"),
                new WorkspaceEvent.StatePatched(2, now, 1, 2, Map.of("type", "search_goal_v1")))));
    }

    @Test
    void rejectsOrphanToolResultsDuringReplay() {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        AgentMessage.ToolResult orphan = new AgentMessage.ToolResult(
                1, "result-1", "request-1", "turn-1", "run-1", 1,
                "call-1", "search_direct", "v1",
                AgentMessage.ToolResultStatus.SUCCEEDED,
                "ok", Map.of(), Map.of(), Map.of(), List.of(),
                null, null, false, 1);

        assertThrows(IllegalArgumentException.class, () -> AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "search-assistant", "v2"),
                new WorkspaceEvent.ToolResultMessage(2, now, orphan))));
    }

    @Test
    void rejectsAssistantToolCallsWithoutAResult() {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        AgentMessage.Assistant assistant = new AgentMessage.Assistant(
                1, "assistant-1", "request-1", "turn-1", "run-1", 1,
                "search", null, false,
                List.of(new AgentMessage.ToolCall(
                        "call-1", "search_direct", 0, Map.of("query", "露营"))));

        assertThrows(IllegalArgumentException.class, () -> AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "search-assistant", "v2"),
                new WorkspaceEvent.AssistantMessage(2, now, assistant))));
    }

    @Test
    void projectsClarificationOutcomeAsSuspended() {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");

        AgentSession session = AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "search-assistant", "v2"),
                new WorkspaceEvent.UserMessage(2, now, "request-1", "turn-1", "找内容"),
                new WorkspaceEvent.AssistantMessage(3, now, new AgentMessage.Assistant(
                        1, "assistant-1", "request-1", "turn-1", "run-1", 1,
                        "请补充主题", null, false, List.of())),
                new WorkspaceEvent.RunCompleted(
                        4, now, "run-1", AgentTerminalState.NEED_CLARIFICATION, null)));

        assertEquals(AgentSessionStatus.SUSPENDED, session.status());
    }
}
