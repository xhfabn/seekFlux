package io.seekflux.platform.agentruntime.session;

import java.util.Comparator;
import java.util.List;

public record AgentSession(
        String sessionId,
        String agentId,
        String agentVersion,
        long position,
        AgentSessionStatus status,
        List<WorkspaceEvent> events) {

    public AgentSession {
        events = events == null ? List.of() : events.stream()
                .sorted(Comparator.comparingLong(WorkspaceEvent::position))
                .toList();
    }

    public static AgentSession replay(String sessionId, List<WorkspaceEvent> events) {
        if (events == null || events.isEmpty()
                || !(events.stream().min(Comparator.comparingLong(WorkspaceEvent::position)).orElseThrow()
                instanceof WorkspaceEvent.SessionCreated created)) {
            throw new IllegalArgumentException("an agent session must start with SessionCreated");
        }
        List<WorkspaceEvent> ordered = events.stream()
                .sorted(Comparator.comparingLong(WorkspaceEvent::position))
                .toList();
        AgentSessionStatus status = AgentSessionStatus.IDLE;
        long position = 0;
        for (WorkspaceEvent event : ordered) {
            if (event.position() <= position) {
                throw new IllegalArgumentException("workspace event positions must be strictly increasing");
            }
            position = event.position();
            status = switch (event) {
                case WorkspaceEvent.SessionCreated ignored -> AgentSessionStatus.IDLE;
                case WorkspaceEvent.UserMessage ignored -> AgentSessionStatus.EXECUTING;
                case WorkspaceEvent.RunCompleted completed -> AgentSessionStatus.COMPLETED;
                case WorkspaceEvent.RunCancelled cancelled -> AgentSessionStatus.COMPLETED;
                case WorkspaceEvent.RunFailed failed -> AgentSessionStatus.COMPLETED;
            };
        }
        return new AgentSession(
                sessionId,
                created.agentId(),
                created.agentVersion(),
                position,
                status,
                ordered);
    }
}
