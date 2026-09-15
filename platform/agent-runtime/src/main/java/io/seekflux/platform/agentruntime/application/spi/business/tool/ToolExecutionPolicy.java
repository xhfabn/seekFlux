package io.seekflux.platform.agentruntime.application.spi.business.tool;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import java.util.Map;

@FunctionalInterface
public interface ToolExecutionPolicy {

    ToolExecutionPolicy ALLOW_ALL = context -> Decision.allow(context.arguments());

    Decision evaluate(Context context);

    record Context(
            AgentRunRequest request,
            String toolName,
            String toolSchemaVersion,
            AgentTool.Effect effect,
            int step,
            int callIndex,
            Map<String, Object> arguments) {
        public Context {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record Decision(Action action, Map<String, Object> arguments, String reason) {
        public Decision {
            if (action == null) {
                throw new IllegalArgumentException("Tool policy action is required");
            }
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }

        public static Decision allow(Map<String, Object> arguments) {
            return new Decision(Action.ALLOW, arguments, null);
        }

        public static Decision modify(Map<String, Object> arguments, String reason) {
            return new Decision(Action.MODIFY, arguments, reason);
        }

        public static Decision deny(String reason) {
            return new Decision(Action.DENY, Map.of(), reason);
        }

        public static Decision needApproval(String reason) {
            return new Decision(Action.NEED_APPROVAL, Map.of(), reason);
        }
    }

    enum Action { ALLOW, MODIFY, DENY, NEED_APPROVAL }
}
