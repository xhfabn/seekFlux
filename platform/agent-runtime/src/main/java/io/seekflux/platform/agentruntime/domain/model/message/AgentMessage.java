package io.seekflux.platform.agentruntime.domain.model.message;

import java.util.List;
import java.util.Map;

public sealed interface AgentMessage {

    int schemaVersion();

    String messageId();

    String requestId();

    String turnId();

    String segmentId();

    String agentRunId();

    int step();

    record Assistant(
            int schemaVersion,
            String messageId,
            String requestId,
            String turnId,
            String segmentId,
            String agentRunId,
            int step,
            String content,
            String reasoning,
            boolean reasoningReplayable,
            List<ToolCall> toolCalls) implements AgentMessage {

        public Assistant(
                int schemaVersion,
                String messageId,
                String requestId,
                String turnId,
                String agentRunId,
                int step,
                String content,
                String reasoning,
                boolean reasoningReplayable,
                List<ToolCall> toolCalls) {
            this(schemaVersion, messageId, requestId, turnId, turnId, agentRunId, step,
                    content, reasoning, reasoningReplayable, toolCalls);
        }

        public Assistant {
            requireEnvelope(
                    schemaVersion, messageId, requestId, turnId, segmentId, agentRunId, step);
            content = normalize(content);
            reasoning = normalize(reasoning);
            if (reasoning == null) {
                reasoningReplayable = false;
            }
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            if (content == null && toolCalls.isEmpty()) {
                throw new IllegalArgumentException("an assistant message must contain content or tool calls");
            }
        }
    }

    record ToolCall(
            String toolCallId,
            String toolName,
            int index,
            Map<String, Object> arguments) {

        public ToolCall {
            requireText(toolCallId, "tool call id");
            requireText(toolName, "tool name");
            if (index < 0) {
                throw new IllegalArgumentException("tool call index must not be negative");
            }
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        }
    }

    record ToolResult(
            int schemaVersion,
            String messageId,
            String requestId,
            String turnId,
            String segmentId,
            String agentRunId,
            int step,
            String toolCallId,
            String toolName,
            String toolSchemaVersion,
            ToolResultStatus status,
            String modelContent,
            Map<String, Object> rawContents,
            Map<String, Object> displayContents,
            Map<String, Object> structuredData,
            List<Map<String, Object>> resources,
            String errorCode,
            String linkedTraceId,
            boolean argumentsRepaired,
            long tookMillis) implements AgentMessage {

        public ToolResult(
                int schemaVersion,
                String messageId,
                String requestId,
                String turnId,
                String agentRunId,
                int step,
                String toolCallId,
                String toolName,
                String toolSchemaVersion,
                ToolResultStatus status,
                String modelContent,
                Map<String, Object> rawContents,
                Map<String, Object> displayContents,
                Map<String, Object> structuredData,
                List<Map<String, Object>> resources,
                String errorCode,
                String linkedTraceId,
                boolean argumentsRepaired,
                long tookMillis) {
            this(schemaVersion, messageId, requestId, turnId, turnId, agentRunId, step,
                    toolCallId, toolName, toolSchemaVersion, status, modelContent,
                    rawContents, displayContents, structuredData, resources, errorCode,
                    linkedTraceId, argumentsRepaired, tookMillis);
        }

        public ToolResult {
            requireEnvelope(
                    schemaVersion, messageId, requestId, turnId, segmentId, agentRunId, step);
            requireText(toolCallId, "tool call id");
            requireText(toolName, "tool name");
            requireText(toolSchemaVersion, "tool schema version");
            if (status == null) {
                throw new IllegalArgumentException("tool result status must not be null");
            }
            modelContent = normalize(modelContent);
            rawContents = rawContents == null ? Map.of() : Map.copyOf(rawContents);
            displayContents = displayContents == null ? Map.of() : Map.copyOf(displayContents);
            structuredData = structuredData == null ? Map.of() : Map.copyOf(structuredData);
            resources = resources == null ? List.of() : resources.stream().map(Map::copyOf).toList();
            if (tookMillis < 0) {
                throw new IllegalArgumentException("tool result timing must not be negative");
            }
        }
    }

    enum ToolResultStatus {
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT,
        WAITING
    }

    private static void requireEnvelope(
            int schemaVersion,
            String messageId,
            String requestId,
            String turnId,
            String segmentId,
            String agentRunId,
            int step) {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("message schema version must be positive");
        }
        requireText(messageId, "message id");
        requireText(requestId, "request id");
        requireText(turnId, "turn id");
        requireText(segmentId, "segment id");
        requireText(agentRunId, "agent run id");
        if (step < 1) {
            throw new IllegalArgumentException("message step must be positive");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
