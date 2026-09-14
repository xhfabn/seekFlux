package io.seekflux.platform.agentruntime.domain.model.session;

import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public sealed interface WorkspaceEvent {

    long position();

    Instant eventTime();

    record SessionCreated(
            long position,
            Instant eventTime,
            String agentId,
            String agentVersion) implements WorkspaceEvent {
    }

    record UserMessage(
            long position,
            Instant eventTime,
            int schemaVersion,
            String messageId,
            String requestId,
            String turnId,
            String text) implements WorkspaceEvent {

        public UserMessage(
                long position,
                Instant eventTime,
                String requestId,
                String turnId,
                String text) {
            this(position, eventTime, 1, legacyMessageId(requestId, turnId), requestId, turnId, text);
        }

        public UserMessage {
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("message schema version must be positive");
            }
            if (messageId == null || messageId.isBlank()) {
                messageId = legacyMessageId(requestId, turnId);
            }
        }
    }

    record AssistantMessage(
            long position,
            Instant eventTime,
            AgentMessage.Assistant message) implements WorkspaceEvent {
    }

    record ToolResultMessage(
            long position,
            Instant eventTime,
            AgentMessage.ToolResult message) implements WorkspaceEvent {
    }

    record StatePatched(
            long position,
            Instant eventTime,
            long baseVersion,
            long stateVersion,
            Map<String, Object> state) implements WorkspaceEvent {

        public StatePatched {
            state = state == null ? Map.of() : Map.copyOf(state);
        }
    }

    record RunCompleted(
            long position,
            Instant eventTime,
            String agentRunId,
            AgentTerminalState state,
            String fallbackReason) implements WorkspaceEvent {
    }

    record RunCancelled(
            long position,
            Instant eventTime,
            String agentRunId,
            String reason) implements WorkspaceEvent {
    }

    record RunFailed(
            long position,
            Instant eventTime,
            String agentRunId,
            String errorCode) implements WorkspaceEvent {
    }

    private static String legacyMessageId(String requestId, String turnId) {
        String identity = "user:" + requestId + ":" + turnId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
