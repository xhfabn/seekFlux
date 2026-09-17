package io.seekflux.platform.agentruntime.domain.model.session;

import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        Set<String> suspendedToolCalls = new HashSet<>();
        Map<String, WaitState> pendingWaits = new LinkedHashMap<>();
        Map<String, WorkspaceEvent.QueuedUserMessage> queuedMessages = new LinkedHashMap<>();
        for (WorkspaceEvent event : ordered) {
            if (event.position() <= position) {
                throw new IllegalArgumentException("workspace event positions must be strictly increasing");
            }
            position = event.position();
            status = switch (event) {
                case WorkspaceEvent.SessionCreated ignored -> AgentSessionStatus.IDLE;
                case WorkspaceEvent.UserMessage message -> {
                    requireUnique(messageIds, message.messageId(), "message");
                    queuedMessages.remove(message.messageId());
                    yield AgentSessionStatus.EXECUTING;
                }
                case WorkspaceEvent.QueuedUserMessage message -> {
                    if (queuedMessages.putIfAbsent(message.messageId(), message) != null) {
                        throw new IllegalArgumentException("queued message ids must be unique within a session");
                    }
                    yield status;
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
                    suspendedToolCalls.remove(message.toolCallId());
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
                case WorkspaceEvent.WaitSuspended suspended -> {
                    WaitState wait = suspended.waitState();
                    if (pendingWaits.putIfAbsent(wait.waitId(), wait) != null) {
                        throw new IllegalArgumentException("wait ids must be unique within a session");
                    }
                    suspendedToolCalls.add(wait.toolCallId());
                    yield AgentSessionStatus.SUSPENDED;
                }
                case WorkspaceEvent.WaitResolved resolved -> {
                    var resolution = resolved.resolution();
                    WaitState removed = pendingWaits.remove(resolution.waitId());
                    if (removed == null
                            && pendingWaits.values().stream().noneMatch(wait ->
                                    wait.waitId().equals(resolution.waitId()))) {
                        throw new IllegalArgumentException("wait resolution must follow a suspension");
                    }
                    yield AgentSessionStatus.EXECUTING;
                }
                case WorkspaceEvent.RunCompleted completed ->
                        completed.state() == AgentTerminalState.NEED_CLARIFICATION
                                ? AgentSessionStatus.SUSPENDED
                                : AgentSessionStatus.COMPLETED;
                case WorkspaceEvent.RunCancelled cancelled -> AgentSessionStatus.COMPLETED;
                case WorkspaceEvent.RunFailed failed -> AgentSessionStatus.COMPLETED;
            };
        }
        Set<String> temporarilyPending = status == AgentSessionStatus.COMPLETED
                ? Set.of() : suspendedToolCalls;
        if (!toolResults.containsAll(toolCalls.stream()
                .filter(callId -> !temporarilyPending.contains(callId)).toList())) {
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

    public Optional<WaitState> pendingWait() {
        Map<String, WaitState> pending = new LinkedHashMap<>();
        for (WorkspaceEvent event : events) {
            if (event instanceof WorkspaceEvent.WaitSuspended suspended) {
                pending.put(suspended.waitState().waitId(), suspended.waitState());
            } else if (event instanceof WorkspaceEvent.WaitResolved resolved) {
                pending.remove(resolved.resolution().waitId());
            }
        }
        if (pending.size() > 1) {
            throw new IllegalStateException("a session cannot contain multiple pending waits");
        }
        return pending.values().stream().findFirst();
    }

    public Optional<WaitState> waitState(String waitId) {
        return events.stream()
                .filter(WorkspaceEvent.WaitSuspended.class::isInstance)
                .map(WorkspaceEvent.WaitSuspended.class::cast)
                .map(WorkspaceEvent.WaitSuspended::waitState)
                .filter(wait -> wait.waitId().equals(waitId))
                .findFirst();
    }

    public List<WorkspaceEvent.QueuedUserMessage> queuedMessages() {
        Map<String, WorkspaceEvent.QueuedUserMessage> pending = new LinkedHashMap<>();
        for (WorkspaceEvent event : events) {
            if (event instanceof WorkspaceEvent.QueuedUserMessage queued) {
                pending.put(queued.messageId(), queued);
            } else if (event instanceof WorkspaceEvent.UserMessage promoted) {
                pending.remove(promoted.messageId());
            }
        }
        return List.copyOf(pending.values());
    }

    public java.util.Optional<WorkspaceEvent.QueuedUserMessage> promotedQueuedExecution() {
        if (status != AgentSessionStatus.EXECUTING) {
            return java.util.Optional.empty();
        }
        WorkspaceEvent.UserMessage latestUser = null;
        for (WorkspaceEvent event : events) {
            if (event instanceof WorkspaceEvent.UserMessage user) {
                latestUser = user;
            }
        }
        if (latestUser == null) {
            return java.util.Optional.empty();
        }
        String messageId = latestUser.messageId();
        return events.stream()
                .filter(WorkspaceEvent.QueuedUserMessage.class::isInstance)
                .map(WorkspaceEvent.QueuedUserMessage.class::cast)
                .filter(queued -> queued.messageId().equals(messageId))
                .findFirst();
    }

    public boolean hasOnlyQueuedEventsAfter(long cutoff) {
        return events.stream()
                .filter(event -> event.position() > cutoff)
                .allMatch(WorkspaceEvent.QueuedUserMessage.class::isInstance);
    }

    private static void requireUnique(Set<String> values, String value, String label) {
        if (!values.add(value)) {
            throw new IllegalArgumentException(label + " ids must be unique within a session");
        }
    }
}
