package io.seekflux.platform.agentruntime.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
