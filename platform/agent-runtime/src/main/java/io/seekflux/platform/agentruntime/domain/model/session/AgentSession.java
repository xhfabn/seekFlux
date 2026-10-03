package io.seekflux.platform.agentruntime.domain.model.session;

import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
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
        List<WorkspaceEvent> events,
        AgentSessionSnapshot baseline) {

    public AgentSession(String sessionId, String agentId, String agentVersion, long position,
            long stateVersion, Map<String, Object> workspaceState, AgentSessionStatus status,
            List<WorkspaceEvent> events) {
        this(sessionId, agentId, agentVersion, position, stateVersion, workspaceState, status, events, null);
    }

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
        return restore(new AgentSessionSnapshot(1, sessionId, created.agentId(), created.agentVersion(),
                0, 0, Map.of(), AgentSessionStatus.IDLE, CapabilityActivationState.EMPTY, Set.of(), List.of()),
                List.of(), events);
    }

    /** Historical views hydrate references; only the tail is applied to the state reducer. */
    public static AgentSession restore(AgentSessionSnapshot snapshot,
            List<WorkspaceEvent> historicalViews, List<WorkspaceEvent> tail) {
        List<WorkspaceEvent> ordered = tail.stream()
                .sorted(Comparator.comparingLong(WorkspaceEvent::position))
                .toList();
        AgentSessionStatus status = snapshot.status();
        long position = snapshot.position();
        long stateVersion = snapshot.stateVersion();
        Map<String, Object> workspaceState = snapshot.workspaceState();
        Set<String> messageIds = new HashSet<>();
        Set<String> toolCalls = new HashSet<>(snapshot.pendingToolCallIds());
        Set<String> toolResults = new HashSet<>();
        Set<String> suspendedToolCalls = new HashSet<>();
        Map<String, WaitState> pendingWaits = new LinkedHashMap<>();
        Map<String, WorkspaceEvent.QueuedUserMessage> queuedMessages = new LinkedHashMap<>();
        for (WorkspaceEvent view : historicalViews) {
            if (view instanceof WorkspaceEvent.WaitSuspended wait
                    && snapshot.retainedEventPositions().contains(view.position())) {
                pendingWaits.put(wait.waitState().waitId(), wait.waitState());
            }
            if (view instanceof WorkspaceEvent.QueuedUserMessage queued
                    && snapshot.retainedEventPositions().contains(view.position())) {
                queuedMessages.put(queued.messageId(), queued);
            }
        }
        for (WorkspaceEvent event : ordered) {
            if (event.position() <= position) {
                throw new IllegalArgumentException("workspace event positions must be strictly increasing");
            }
            position = event.position();
            status = switch (event) {
                case WorkspaceEvent.ExecutionRecorded ignored -> status;
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
                case WorkspaceEvent.CapabilitiesChanged ignored -> status;
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
                || status == AgentSessionStatus.IDLE ? Set.of() : toolCalls;
        if (!toolResults.containsAll(toolCalls.stream()
                .filter(callId -> !temporarilyPending.contains(callId)).toList())) {
            throw new IllegalArgumentException("every assistant tool call must have exactly one tool result");
        }
        return new AgentSession(
                snapshot.sessionId(),
                snapshot.agentId(),
                snapshot.agentVersion(),
                position,
                stateVersion,
                workspaceState,
                status,
                java.util.stream.Stream.concat(historicalViews.stream(), ordered.stream())
                        .collect(java.util.stream.Collectors.toMap(WorkspaceEvent::position, event -> event,
                                (left, right) -> right, java.util.TreeMap::new)).values().stream().toList(),
                snapshot);
    }

    public CapabilityActivationState capabilityState() {
        CapabilityActivationState state = baseline == null ? CapabilityActivationState.EMPTY : baseline.capabilities();
        for (WorkspaceEvent event : events) {
            if (baseline != null && event.position() <= baseline.position()) continue;
            if (event instanceof WorkspaceEvent.CapabilitiesChanged changed) {
                if (changed.baseVersion() != state.version()) {
                    throw new IllegalStateException("capability activation versions must be contiguous");
                }
                state = changed.state();
            }
        }
        return state;
    }

    public boolean hasCapabilityOperation(String operationId) {
        return operationId != null && events.stream()
                .filter(WorkspaceEvent.CapabilitiesChanged.class::isInstance)
                .map(WorkspaceEvent.CapabilitiesChanged.class::cast)
                .anyMatch(event -> event.operationId().equals(operationId));
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

    public boolean hasOnlyExecutionEventsAfter(long cutoff, String requestId) {
        return events.stream().filter(event -> event.position() > cutoff).allMatch(event ->
                event instanceof WorkspaceEvent.QueuedUserMessage
                || event instanceof WorkspaceEvent.ExecutionRecorded execution
                        && (requestId.equals(execution.requestId()) || execution.state().containsKey("summaryId"))
                || event instanceof WorkspaceEvent.AssistantMessage assistant && requestId.equals(assistant.message().requestId())
                || event instanceof WorkspaceEvent.ToolResultMessage tool && requestId.equals(tool.message().requestId()));
    }

    public AgentSessionSnapshot snapshot() {
        Set<String> pendingCalls = new HashSet<>(baseline == null ? Set.of() : baseline.pendingToolCallIds());
        List<Long> retained = new java.util.ArrayList<>();
        queuedMessages().forEach(event -> retained.add(event.position()));
        pendingWait().ifPresent(wait -> events.stream()
                .filter(WorkspaceEvent.WaitSuspended.class::isInstance)
                .map(WorkspaceEvent.WaitSuspended.class::cast)
                .filter(event -> event.waitState().waitId().equals(wait.waitId()))
                .forEach(event -> retained.add(event.position())));
        promotedQueuedExecution().ifPresent(event -> retained.add(event.position()));
        events.stream().filter(WorkspaceEvent.UserMessage.class::isInstance)
                .max(Comparator.comparingLong(WorkspaceEvent::position))
                .ifPresent(event -> retained.add(event.position()));
        for (WorkspaceEvent event : events) {
            if (event instanceof WorkspaceEvent.AssistantMessage assistant) {
                assistant.message().toolCalls().forEach(call -> pendingCalls.add(call.toolCallId()));
            } else if (event instanceof WorkspaceEvent.ToolResultMessage result) {
                pendingCalls.remove(result.message().toolCallId());
            }
        }
        return new AgentSessionSnapshot(1, sessionId, agentId, agentVersion, position, stateVersion,
                workspaceState, status, capabilityState(), pendingCalls, retained.stream().distinct().sorted().toList());
    }

    private static void requireUnique(Set<String> values, String value, String label) {
        if (!values.add(value)) {
            throw new IllegalArgumentException(label + " ids must be unique within a session");
        }
    }
}
