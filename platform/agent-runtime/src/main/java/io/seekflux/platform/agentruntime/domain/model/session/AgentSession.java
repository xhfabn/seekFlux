package io.seekflux.platform.agentruntime.domain.model.session;

import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Set<String> messageIds = new HashSet<>();
        Set<String> toolCalls = new HashSet<>();
        Set<String> toolResults = new HashSet<>();
        for (WorkspaceEvent event : ordered) {
            if (event.position() <= position) {
                throw new IllegalArgumentException("workspace event positions must be strictly increasing");
            }
            position = event.position();
            status = switch (event) {
                case WorkspaceEvent.SessionCreated ignored -> AgentSessionStatus.IDLE;
                case WorkspaceEvent.UserMessage message -> {
                    requireUnique(messageIds, message.messageId(), "message");
                    yield AgentSessionStatus.EXECUTING;
                }
                case WorkspaceEvent.AssistantMessage eventMessage -> {
                    var message = eventMessage.message();
                    requireUnique(messageIds, message.messageId(), "message");
                    for (var toolCall : message.toolCalls()) {
                        requireUnique(toolCalls, toolCall.toolCallId(), "tool call");
                    }
                    yield status;
                }
                case WorkspaceEvent.ToolResultMessage eventMessage -> {
                    var message = eventMessage.message();
                    requireUnique(messageIds, message.messageId(), "message");
                    if (!toolCalls.contains(message.toolCallId())) {
                        throw new IllegalArgumentException("a tool result must follow its assistant tool call");
                    }
                    requireUnique(toolResults, message.toolCallId(), "tool result");
                    yield status;
                }
                case WorkspaceEvent.StatePatched patched -> {
                    if (patched.baseVersion() != stateVersion
                            || patched.stateVersion() != stateVersion + 1) {
                        throw new IllegalArgumentException("workspace state versions must be contiguous");
                    }
                    stateVersion = patched.stateVersion();
                    workspaceState = patched.state();
                    yield status;
                }
                case WorkspaceEvent.RunCompleted completed ->
                        completed.state() == AgentTerminalState.NEED_CLARIFICATION
                                ? AgentSessionStatus.SUSPENDED
                                : AgentSessionStatus.COMPLETED;
                case WorkspaceEvent.RunCancelled cancelled -> AgentSessionStatus.COMPLETED;
                case WorkspaceEvent.RunFailed failed -> AgentSessionStatus.COMPLETED;
            };
        }
        if (!toolCalls.equals(toolResults)) {
            throw new IllegalArgumentException("every assistant tool call must have exactly one tool result");
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

    private static void requireUnique(Set<String> values, String value, String label) {
        if (!values.add(value)) {
            throw new IllegalArgumentException(label + " ids must be unique within a session");
        }
    }
}
