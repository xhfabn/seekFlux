package io.seekflux.platform.agentruntime.application.spi.business.agent;

import java.time.Duration;
import java.util.Map;

/** Adapter boundary for idempotently starting/cancelling child, handoff or fork executions. */
public interface DelegatedAgentLauncher {

    LaunchResult launch(Command command);

    boolean cancel(String operationId, String childSessionId, String reason);

    record Command(
            String operationId,
            Mode mode,
            String parentSessionId,
            String parentRequestId,
            String parentTurnId,
            String childAgentId,
            String requestedChildSessionId,
            String input,
            Map<String, Object> identity,
            int depth,
            long parentRemainingBudgetMillis,
            long budgetMillis,
            Duration timeout) {

        public Command {
            requireText(operationId, "operation id");
            requireText(parentSessionId, "parent session id");
            requireText(parentRequestId, "parent request id");
            requireText(parentTurnId, "parent turn id");
            requireText(childAgentId, "child agent id");
            requireText(requestedChildSessionId, "requested child session id");
            requireText(input, "child input");
            if (mode == null || depth < 1 || parentRemainingBudgetMillis < 1 || budgetMillis < 1
                    || timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("delegated execution limits are invalid");
            }
            identity = identity == null ? Map.of() : Map.copyOf(identity);
        }
    }

    record LaunchResult(String childSessionId, boolean duplicate) {
        public LaunchResult {
            requireText(childSessionId, "child session id");
        }
    }

    enum Mode {
        CHILD_AGENT,
        HANDOFF,
        FORK
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
