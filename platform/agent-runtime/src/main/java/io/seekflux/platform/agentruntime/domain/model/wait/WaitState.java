package io.seekflux.platform.agentruntime.domain.model.wait;

import java.time.Instant;
import java.util.Map;

/** Durable, typed reason why an Agent execution is suspended. */
public sealed interface WaitState permits WaitState.Hitl, WaitState.AsyncTask,
        WaitState.Waitpoint, WaitState.Handoff, WaitState.ChildAgent {

    int schemaVersion();

    String waitId();

    String sessionId();

    String requestId();

    String turnId();

    String checkpointId();

    String toolCallId();

    Instant createdAt();

    Instant deadlineAt();

    MissingPendingPolicy missingPendingPolicy();

    default WaitType type() {
        return switch (this) {
            case Hitl ignored -> WaitType.HITL;
            case AsyncTask ignored -> WaitType.ASYNC_TASK;
            case Waitpoint ignored -> WaitType.WAITPOINT;
            case Handoff ignored -> WaitType.HANDOFF;
            case ChildAgent ignored -> WaitType.CHILD_AGENT;
        };
    }

    default boolean accepts(WaitResolution.Outcome outcome) {
        if (outcome == null) {
            return false;
        }
        return switch (this) {
            case Hitl ignored -> outcome == WaitResolution.Outcome.APPROVED
                    || outcome == WaitResolution.Outcome.DENIED
                    || outcome == WaitResolution.Outcome.TIMED_OUT
                    || outcome == WaitResolution.Outcome.CANCELLED
                    || outcome == WaitResolution.Outcome.FAILED;
            default -> outcome == WaitResolution.Outcome.COMPLETED
                    || outcome == WaitResolution.Outcome.TIMED_OUT
                    || outcome == WaitResolution.Outcome.CANCELLED
                    || outcome == WaitResolution.Outcome.FAILED;
        };
    }

    record Hitl(
            int schemaVersion,
            String waitId,
            String sessionId,
            String requestId,
            String turnId,
            String checkpointId,
            String toolCallId,
            Instant createdAt,
            Instant deadlineAt,
            String reason,
            String toolName,
            Map<String, Object> proposedArguments) implements WaitState {

        public Hitl {
            validateEnvelope(schemaVersion, waitId, sessionId, requestId, turnId,
                    checkpointId, toolCallId, createdAt, deadlineAt);
            requireText(reason, "approval reason");
            requireText(toolName, "tool name");
            proposedArguments = proposedArguments == null
                    ? Map.of() : Map.copyOf(proposedArguments);
        }

        @Override
        public MissingPendingPolicy missingPendingPolicy() {
            return MissingPendingPolicy.FAIL_FAST;
        }
    }

    record AsyncTask(
            int schemaVersion,
            String waitId,
            String sessionId,
            String requestId,
            String turnId,
            String checkpointId,
            String toolCallId,
            Instant createdAt,
            Instant deadlineAt,
            String taskId,
            String callbackType) implements WaitState {

        public AsyncTask {
            validateEnvelope(schemaVersion, waitId, sessionId, requestId, turnId,
                    checkpointId, toolCallId, createdAt, deadlineAt);
            requireText(taskId, "async task id");
            requireText(callbackType, "callback type");
        }

        @Override
        public MissingPendingPolicy missingPendingPolicy() {
            return MissingPendingPolicy.TOLERATE_CALLBACK_RESULT;
        }
    }

    record Waitpoint(
            int schemaVersion,
            String waitId,
            String sessionId,
            String requestId,
            String turnId,
            String checkpointId,
            String toolCallId,
            Instant createdAt,
            Instant deadlineAt,
            String key,
            Map<String, Object> condition) implements WaitState {

        public Waitpoint {
            validateEnvelope(schemaVersion, waitId, sessionId, requestId, turnId,
                    checkpointId, toolCallId, createdAt, deadlineAt);
            requireText(key, "waitpoint key");
            condition = condition == null ? Map.of() : Map.copyOf(condition);
        }

        @Override
        public MissingPendingPolicy missingPendingPolicy() {
            return MissingPendingPolicy.FAIL_FAST;
        }
    }

    record Handoff(
            int schemaVersion,
            String waitId,
            String sessionId,
            String requestId,
            String turnId,
            String checkpointId,
            String toolCallId,
            Instant createdAt,
            Instant deadlineAt,
            String targetAgentId,
            String targetSessionId) implements WaitState {

        public Handoff {
            validateEnvelope(schemaVersion, waitId, sessionId, requestId, turnId,
                    checkpointId, toolCallId, createdAt, deadlineAt);
            requireText(targetAgentId, "target agent id");
            requireText(targetSessionId, "target session id");
        }

        @Override
        public MissingPendingPolicy missingPendingPolicy() {
            return MissingPendingPolicy.FAIL_FAST;
        }
    }

    record ChildAgent(
            int schemaVersion,
            String waitId,
            String sessionId,
            String requestId,
            String turnId,
            String checkpointId,
            String toolCallId,
            Instant createdAt,
            Instant deadlineAt,
            String childAgentId,
            String childSessionId,
            int depth,
            long remainingBudgetMillis) implements WaitState {

        public ChildAgent {
            validateEnvelope(schemaVersion, waitId, sessionId, requestId, turnId,
                    checkpointId, toolCallId, createdAt, deadlineAt);
            requireText(childAgentId, "child agent id");
            requireText(childSessionId, "child session id");
            if (depth < 1 || remainingBudgetMillis < 1) {
                throw new IllegalArgumentException(
                        "child depth and remaining budget must be positive");
            }
        }

        @Override
        public MissingPendingPolicy missingPendingPolicy() {
            return MissingPendingPolicy.TOLERATE_CALLBACK_RESULT;
        }
    }

    enum WaitType {
        HITL,
        ASYNC_TASK,
        WAITPOINT,
        HANDOFF,
        CHILD_AGENT
    }

    enum MissingPendingPolicy {
        FAIL_FAST,
        TOLERATE_CALLBACK_RESULT
    }

    private static void validateEnvelope(
            int schemaVersion,
            String waitId,
            String sessionId,
            String requestId,
            String turnId,
            String checkpointId,
            String toolCallId,
            Instant createdAt,
            Instant deadlineAt) {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("wait schema version must be positive");
        }
        requireText(waitId, "wait id");
        requireText(sessionId, "session id");
        requireText(requestId, "request id");
        requireText(turnId, "turn id");
        requireText(checkpointId, "checkpoint id");
        requireText(toolCallId, "tool call id");
        if (createdAt == null || deadlineAt == null || deadlineAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("wait timestamps are invalid");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
