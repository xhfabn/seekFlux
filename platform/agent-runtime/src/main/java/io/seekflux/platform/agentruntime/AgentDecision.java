package io.seekflux.platform.agentruntime;

import java.util.Map;
import java.util.List;

public sealed interface AgentDecision {

    record CallTool(String toolName, Map<String, Object> arguments) implements AgentDecision {
        public CallTool {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("tool name must not be blank");
            }
            toolName = toolName.trim();
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record CallTools(List<ToolCall> calls) implements AgentDecision {
        public CallTools {
            calls = calls == null ? List.of() : List.copyOf(calls);
            if (calls.size() < 2) {
                throw new IllegalArgumentException("parallel tool decision requires at least two calls");
            }
        }
    }

    record ToolCall(String toolName, Map<String, Object> arguments) {
        public ToolCall {
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("tool name must not be blank");
            }
            toolName = toolName.trim();
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record Complete(Map<String, Object> output) implements AgentDecision {
        public Complete {
            output = output == null ? Map.of() : Map.copyOf(output);
        }
    }

    record Clarify(String question) implements AgentDecision {
        public Clarify {
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("clarification question must not be blank");
            }
            question = question.trim();
        }
    }

    record Fallback(String reason) implements AgentDecision {
        public Fallback {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("fallback reason must not be blank");
            }
            reason = reason.trim();
        }
    }
}
