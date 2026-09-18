package io.seekflux.platform.agentruntime.domain.model.session;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
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

    record QueuedUserMessage(
            long position,
            Instant eventTime,
            int schemaVersion,
            String messageId,
            AgentRunRequest request,
            Map<String, Object> persistentFeatures) implements WorkspaceEvent {

        public QueuedUserMessage(
                long position,
                Instant eventTime,
                int schemaVersion,
                String messageId,
                AgentRunRequest request) {
            this(position, eventTime, schemaVersion, messageId, request, Map.of());
        }

        public QueuedUserMessage {
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("message schema version must be positive");
            }
            if (messageId == null || messageId.isBlank()) {
                throw new IllegalArgumentException("queued message id must not be blank");
            }
            if (request == null) {
                throw new IllegalArgumentException("queued request must not be null");
            }
            persistentFeatures = persistentFeatures == null
                    ? Map.of() : Map.copyOf(persistentFeatures);
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

    record CapabilitiesChanged(
            long position,
            Instant eventTime,
            int schemaVersion,
            String operationId,
            String actor,
            long baseVersion,
            CapabilityActivationState state) implements WorkspaceEvent {

        public CapabilitiesChanged {
            if (schemaVersion < 1) {
                throw new IllegalArgumentException("capability event schema version must be positive");
            }
            if (operationId == null || operationId.isBlank()) {
                throw new IllegalArgumentException("capability operation id must not be blank");
            }
            if (actor == null || actor.isBlank()) {
                throw new IllegalArgumentException("capability actor must not be blank");
            }
            if (state == null || state.version() != baseVersion + 1) {
                throw new IllegalArgumentException("capability event versions must be contiguous");
            }
        }
    }

    record WaitSuspended(
            long position,
            Instant eventTime,
            WaitState waitState) implements WorkspaceEvent {

        public WaitSuspended {
            if (waitState == null) {
                throw new IllegalArgumentException("suspended wait state is required");
            }
        }
    }

    record WaitResolved(
            long position,
            Instant eventTime,
            WaitResolution resolution) implements WorkspaceEvent {

        public WaitResolved {
            if (resolution == null) {
                throw new IllegalArgumentException("wait resolution is required");
            }
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
