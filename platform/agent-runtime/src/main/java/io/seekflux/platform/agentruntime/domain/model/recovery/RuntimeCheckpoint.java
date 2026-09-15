package io.seekflux.platform.agentruntime.domain.model.recovery;

import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunTrace;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record RuntimeCheckpoint(
        int schemaVersion,
        String checkpointId,
        CheckpointBoundary boundary,
        String sessionId,
        String requestId,
        String attemptId,
        String turnId,
        long fencingToken,
        long messageCutoff,
        AgentRunTrace.DefinitionSnapshot definition,
        int nextStep,
        int toolCallCount,
        long remainingBudgetMillis,
        Map<String, Object> persistentFeatures,
        List<AgentToolObservation> observations,
        List<AgentMessage> messages,
        Set<String> completedInvocations,
        LlmUsage llmUsage,
        List<AgentRunTrace.StepTrace> steps,
        AgentRunResult terminalResult,
        Instant createdAt) {

    public RuntimeCheckpoint {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("checkpoint schema version must be positive");
        }
        requireText(checkpointId, "checkpoint id");
        requireText(sessionId, "session id");
        requireText(requestId, "request id");
        requireText(attemptId, "attempt id");
        requireText(turnId, "turn id");
        if (boundary == null || definition == null || createdAt == null) {
            throw new IllegalArgumentException("checkpoint boundary, definition and time are required");
        }
        if (fencingToken < 0 || messageCutoff < 0 || nextStep < 1
                || toolCallCount < 0 || remainingBudgetMillis < 0) {
            throw new IllegalArgumentException("checkpoint counters must not be negative");
        }
        persistentFeatures = persistentFeatures == null ? Map.of() : Map.copyOf(persistentFeatures);
        observations = observations == null ? List.of() : List.copyOf(observations);
        messages = messages == null ? List.of() : List.copyOf(messages);
        completedInvocations = completedInvocations == null
                ? Set.of() : Set.copyOf(completedInvocations);
        llmUsage = llmUsage == null ? LlmUsage.UNMEASURED : llmUsage;
        steps = steps == null ? List.of() : List.copyOf(steps);
        boolean terminal = boundary == CheckpointBoundary.COMPLETED
                || boundary == CheckpointBoundary.SUSPENDED;
        if (terminal != (terminalResult != null)) {
            throw new IllegalArgumentException("only terminal checkpoints contain a terminal result");
        }
    }

    public boolean terminal() {
        return terminalResult != null;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
