package io.seekflux.platform.agentruntime.session;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

public record AgentSession(
        String sessionId,
        String agentId,
        String agentVersion,
        long position,
        long stateVersion,
        Map<String, Object> workspaceState,
        AgentSessionStatus status,
        List<WorkspaceEvent> events) {

    public AgentSession {
        workspaceState = workspaceState == null ? Map.of() : Map.copyOf(workspaceState);
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
        long stateVersion = 0;
        Map<String, Object> workspaceState = Map.of();
        for (WorkspaceEvent event : ordered) {
            if (event.position() <= position) {
                throw new IllegalArgumentException("workspace event positions must be strictly increasing");
            }
            position = event.position();
            status = switch (event) {
                case WorkspaceEvent.SessionCreated ignored -> AgentSessionStatus.IDLE;
                case WorkspaceEvent.UserMessage ignored -> AgentSessionStatus.EXECUTING;
                case WorkspaceEvent.StatePatched patched -> {
                    if (patched.baseVersion() != stateVersion
                            || patched.stateVersion() != stateVersion + 1) {
                        throw new IllegalArgumentException("workspace state versions must be contiguous");
                    }
                    stateVersion = patched.stateVersion();
                    workspaceState = patched.state();
                    yield status;
                }
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
                stateVersion,
                workspaceState,
                status,
                ordered);
    }
}
