package io.seekflux.platform.agentruntime.domain.model.recovery;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import java.time.Instant;
import java.util.Map;

public record ToolCallJournalEntry(
        int schemaVersion,
        String sessionId,
        String requestId,
        String turnId,
        String attemptId,
        int step,
        int callIndex,
        String toolCallId,
        String toolName,
        String toolSchemaVersion,
        AgentTool.Effect effect,
        ToolJournalStatus status,
        Map<String, Object> arguments,
        String argumentsDigest,
        boolean argumentsRepaired,
        AgentMessage.Assistant assistantMessage,
        AgentToolObservation observation,
        Instant updatedAt) {

    public ToolCallJournalEntry {
        if (schemaVersion < 1 || step < 1 || callIndex < 0) {
            throw new IllegalArgumentException("invalid tool journal version or position");
        }
        requireText(sessionId, "session id");
        requireText(requestId, "request id");
        requireText(turnId, "turn id");
        requireText(attemptId, "attempt id");
        requireText(toolCallId, "tool call id");
        requireText(toolName, "tool name");
        requireText(toolSchemaVersion, "tool schema version");
        requireText(argumentsDigest, "arguments digest");
        if (effect == null || status == null || assistantMessage == null || updatedAt == null) {
            throw new IllegalArgumentException("tool journal effect, status, assistant and time are required");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        if (status.terminal() != (observation != null)) {
            throw new IllegalArgumentException("terminal tool journal states require one observation");
        }
    }

    public ToolCallJournalEntry withStatus(
            ToolJournalStatus nextStatus,
            AgentToolObservation nextObservation,
            Instant time) {
        return forAttempt(attemptId, nextStatus, nextObservation, time);
    }

    public ToolCallJournalEntry forAttempt(
            String nextAttemptId,
            ToolJournalStatus nextStatus,
            AgentToolObservation nextObservation,
            Instant time) {
        return new ToolCallJournalEntry(
                schemaVersion, sessionId, requestId, turnId, nextAttemptId, step, callIndex,
                toolCallId, toolName, toolSchemaVersion, effect, nextStatus, arguments,
                argumentsDigest, argumentsRepaired, assistantMessage, nextObservation, time);
    }

    public boolean safeToRetry() {
        return effect == AgentTool.Effect.READ_ONLY || effect == AgentTool.Effect.IDEMPOTENT;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
