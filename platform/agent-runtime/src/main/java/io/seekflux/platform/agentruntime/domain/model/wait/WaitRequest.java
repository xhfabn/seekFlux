package io.seekflux.platform.agentruntime.domain.model.wait;

import java.time.Duration;
import java.util.Map;

/** A Tool-originated request to suspend the current Agent execution. */
public sealed interface WaitRequest permits WaitRequest.AsyncTask, WaitRequest.Waitpoint,
        WaitRequest.Handoff, WaitRequest.ChildAgent {

    Duration timeout();

    record AsyncTask(String taskId, String callbackType, Duration timeout)
            implements WaitRequest {
        public AsyncTask {
            requireText(taskId, "async task id");
            requireText(callbackType, "callback type");
            validateTimeout(timeout);
        }
    }

    record Waitpoint(String key, Map<String, Object> condition, Duration timeout)
            implements WaitRequest {
        public Waitpoint {
            requireText(key, "waitpoint key");
            condition = condition == null ? Map.of() : Map.copyOf(condition);
            validateTimeout(timeout);
        }
    }

    record Handoff(String targetAgentId, String targetSessionId, Duration timeout)
            implements WaitRequest {
        public Handoff {
            requireText(targetAgentId, "target agent id");
            requireText(targetSessionId, "target session id");
            validateTimeout(timeout);
        }
    }

    record ChildAgent(
            String childAgentId,
            String childSessionId,
            int depth,
            long remainingBudgetMillis,
            Duration timeout) implements WaitRequest {
        public ChildAgent {
            requireText(childAgentId, "child agent id");
            requireText(childSessionId, "child session id");
            if (depth < 1 || remainingBudgetMillis < 1) {
                throw new IllegalArgumentException(
                        "child depth and remaining budget must be positive");
            }
            validateTimeout(timeout);
        }
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("wait timeout must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
