package io.seekflux.platform.agentruntime.domain.model.session;

import static org.junit.jupiter.api.Assertions.*;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class AgentSessionSnapshotTest {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");

    @Test void appliesOnlyTailStateVersionsAndRetainsQueueReferences() {
        var queued = new WorkspaceEvent.QueuedUserMessage(40, NOW, 1, "queued",
                new AgentRunRequest("request-q", "session", "turn-q", "later", Map.of()));
        var baseline = new AgentSessionSnapshot(1, "session", "agent", "v1", 100, 8,
                Map.of("goal", "before"), AgentSessionStatus.EXECUTING,
                CapabilityActivationState.EMPTY, Set.of(), List.of(40L));
        var session = AgentSession.restore(baseline, List.of(queued), List.of(
                new WorkspaceEvent.StatePatched(101, NOW, 8, 9, Map.of("goal", "after")),
                new WorkspaceEvent.UserMessage(102, NOW, 1, "queued", "request-q", "turn-q", "later")));
        assertEquals(9, session.stateVersion());
        assertEquals("after", session.workspaceState().get("goal"));
        assertTrue(session.queuedMessages().isEmpty());
        assertEquals("request-q", session.promotedQueuedExecution().orElseThrow().request().requestId());
        assertEquals(102, session.position());
        assertThrows(IllegalArgumentException.class, () -> AgentSession.restore(baseline, List.of(),
                List.of(new WorkspaceEvent.StatePatched(101, NOW, 0, 1, Map.of()))));
    }

    @Test void permitsInFlightToolAndResolvesItAfterBaseline() {
        var assistant = new AgentMessage.Assistant(1, "assistant", "request", "turn", "attempt", 1,
                "tool decision", null, false, List.of(new AgentMessage.ToolCall("call", "tool", 0, Map.of())));
        var session = AgentSession.replay("session", List.of(
                new WorkspaceEvent.SessionCreated(1, NOW, "agent", "v1"),
                new WorkspaceEvent.UserMessage(2, NOW, "request", "turn", "input"),
                new WorkspaceEvent.AssistantMessage(3, NOW, assistant)));
        var baseline = session.snapshot();
        assertEquals(Set.of("call"), baseline.pendingToolCallIds());
        var result = new AgentMessage.ToolResult(1, "result", "request", "turn", "attempt", 1,
                "call", "tool", "v1", AgentMessage.ToolResultStatus.SUCCEEDED, "ok", Map.of(),
                Map.of(), Map.of(), List.of(), null, null, false, 1);
        var restored = AgentSession.restore(baseline, List.of(new WorkspaceEvent.AssistantMessage(3, NOW, assistant)),
                List.of(new WorkspaceEvent.ToolResultMessage(4, NOW, result)));
        assertTrue(restored.snapshot().pendingToolCallIds().isEmpty());
        assertEquals(4, restored.position());
    }
}
