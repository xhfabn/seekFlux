package io.seekflux.platform.persistence.agent;

import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class WorkspaceMessageCodec {

    EncodedMessage encode(AgentMessage message) {
        if (message instanceof AgentMessage.Assistant assistant) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("agentRunId", assistant.agentRunId());
            payload.put("segmentId", assistant.segmentId());
            payload.put("step", assistant.step());
            payload.put("content", assistant.content());
            payload.put("reasoning", assistant.reasoning());
            payload.put("reasoningReplayable", assistant.reasoningReplayable());
            payload.put("toolCalls", assistant.toolCalls());
            return envelope(assistant, "ASSISTANT_MESSAGE", null, payload);
        }
        AgentMessage.ToolResult result = (AgentMessage.ToolResult) message;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentRunId", result.agentRunId());
        payload.put("segmentId", result.segmentId());
        payload.put("step", result.step());
        payload.put("toolName", result.toolName());
        payload.put("toolSchemaVersion", result.toolSchemaVersion());
        payload.put("status", result.status().name());
        payload.put("modelContent", result.modelContent());
        payload.put("rawContents", result.rawContents());
        payload.put("displayContents", result.displayContents());
        payload.put("structuredData", result.structuredData());
        payload.put("resources", result.resources());
        payload.put("errorCode", result.errorCode());
        payload.put("linkedTraceId", result.linkedTraceId());
        payload.put("argumentsRepaired", result.argumentsRepaired());
        payload.put("tookMillis", result.tookMillis());
        return envelope(result, "TOOL_RESULT_MESSAGE", result.toolCallId(), payload);
    }

    AgentMessage decode(
            String eventType,
            int schemaVersion,
            String messageId,
            String toolCallId,
            String requestId,
            String turnId,
            Map<String, Object> payload) {
        return switch (eventType) {
            case "ASSISTANT_MESSAGE" -> new AgentMessage.Assistant(
                    schemaVersion,
                    messageId,
                    requestId,
                    turnId,
                    stringOrDefault(payload, "segmentId", turnId),
                    string(payload, "agentRunId"),
                    Math.toIntExact(number(payload, "step")),
                    string(payload, "content"),
                    string(payload, "reasoning"),
                    bool(payload, "reasoningReplayable"),
                    toolCalls(payload, "toolCalls"));
            case "TOOL_RESULT_MESSAGE" -> new AgentMessage.ToolResult(
                    schemaVersion,
                    messageId,
                    requestId,
                    turnId,
                    stringOrDefault(payload, "segmentId", turnId),
                    string(payload, "agentRunId"),
                    Math.toIntExact(number(payload, "step")),
                    toolCallId,
                    string(payload, "toolName"),
                    string(payload, "toolSchemaVersion"),
                    AgentMessage.ToolResultStatus.valueOf(string(payload, "status")),
                    string(payload, "modelContent"),
                    mapOrEmpty(payload, "rawContents"),
                    mapOrEmpty(payload, "displayContents"),
                    mapOrEmpty(payload, "structuredData"),
                    listOfMaps(payload, "resources"),
                    string(payload, "errorCode"),
                    string(payload, "linkedTraceId"),
                    bool(payload, "argumentsRepaired"),
                    number(payload, "tookMillis"));
            default -> throw new IllegalArgumentException("not a workspace message event: " + eventType);
        };
    }

    private static EncodedMessage envelope(
            AgentMessage message,
            String eventType,
            String toolCallId,
            Map<String, Object> payload) {
        return new EncodedMessage(
                eventId(message.messageId()),
                eventType,
                message.schemaVersion(),
                message.messageId(),
                toolCallId,
                message.requestId(),
                message.turnId(),
                Collections.unmodifiableMap(new LinkedHashMap<>(payload)));
    }

    private static UUID eventId(String messageId) {
        try {
            return UUID.fromString(messageId);
        } catch (IllegalArgumentException invalidUuid) {
            return UUID.nameUUIDFromBytes(messageId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static List<AgentMessage.ToolCall> toolCalls(
            Map<String, Object> payload,
            String key) {
        return listOfMaps(payload, key).stream()
                .map(call -> new AgentMessage.ToolCall(
                        string(call, "toolCallId"),
                        string(call, "toolName"),
                        Math.toIntExact(number(call, "index")),
                        mapOrEmpty(call, "arguments")))
                .toList();
    }

    private static List<Map<String, Object>> listOfMaps(
            Map<String, Object> payload,
            String key) {
        Object value = payload.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException("workspace event field is not an array: " + key);
        }
        return values.stream().map(item -> {
            if (!(item instanceof Map<?, ?> map)) {
                throw new IllegalStateException("workspace event array item is not an object: " + key);
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((itemKey, itemValue) -> normalized.put(String.valueOf(itemKey), itemValue));
            return Map.copyOf(normalized);
        }).toList();
    }

    private static Map<String, Object> mapOrEmpty(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalStateException("workspace event field is not an object: " + key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((itemKey, itemValue) -> result.put(String.valueOf(itemKey), itemValue));
        return Map.copyOf(result);
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String stringOrDefault(
            Map<String, Object> payload,
            String key,
            String fallback) {
        String value = string(payload, key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("workspace event field is not numeric: " + key);
        }
        return number.longValue();
    }

    private static boolean bool(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Boolean flag && flag;
    }

    record EncodedMessage(
            UUID eventId,
            String eventType,
            int schemaVersion,
            String messageId,
            String toolCallId,
            String requestId,
            String turnId,
            Map<String, Object> payload) {
    }
}
