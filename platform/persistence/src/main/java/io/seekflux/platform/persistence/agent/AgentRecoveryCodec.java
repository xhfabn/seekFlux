package io.seekflux.platform.persistence.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.recovery.CheckpointBoundary;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolJournalStatus;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunTrace;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AgentRecoveryCodec {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper objectMapper;
    private final WorkspaceMessageCodec messageCodec = new WorkspaceMessageCodec();

    AgentRecoveryCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    Map<String, Object> encodeCheckpoint(RuntimeCheckpoint checkpoint) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("definition", objectMapper.convertValue(checkpoint.definition(), MAP));
        payload.put("persistentFeatures", checkpoint.persistentFeatures());
        payload.put("observations", checkpoint.observations().stream()
                .map(value -> objectMapper.convertValue(value, MAP)).toList());
        payload.put("messages", checkpoint.messages().stream().map(this::encodeMessage).toList());
        payload.put("completedInvocations", checkpoint.completedInvocations());
        payload.put("llmUsage", objectMapper.convertValue(checkpoint.llmUsage(), MAP));
        payload.put("steps", checkpoint.steps().stream()
                .map(value -> objectMapper.convertValue(value, MAP)).toList());
        if (checkpoint.terminalResult() != null) {
            payload.put("terminalResult", encodeResult(checkpoint.terminalResult()));
        }
        return payload;
    }

    RuntimeCheckpoint decodeCheckpoint(
            int schemaVersion,
            String checkpointId,
            String boundary,
            String sessionId,
            String requestId,
            String turnId,
            String attemptId,
            long fencingToken,
            long messageCutoff,
            int nextStep,
            int toolCallCount,
            long remainingBudgetMillis,
            Instant createdAt,
            Map<String, Object> payload) {
        return new RuntimeCheckpoint(
                schemaVersion,
                checkpointId,
                CheckpointBoundary.valueOf(boundary),
                sessionId,
                requestId,
                attemptId,
                turnId,
                fencingToken,
                messageCutoff,
                objectMapper.convertValue(required(payload, "definition"),
                        AgentRunTrace.DefinitionSnapshot.class),
                nextStep,
                toolCallCount,
                remainingBudgetMillis,
                map(payload, "persistentFeatures"),
                list(payload, "observations").stream()
                        .map(value -> objectMapper.convertValue(value, AgentToolObservation.class))
                        .toList(),
                list(payload, "messages").stream().map(this::decodeMessage).toList(),
                Set.copyOf(stringList(payload, "completedInvocations")),
                objectMapper.convertValue(required(payload, "llmUsage"), LlmUsage.class),
                list(payload, "steps").stream()
                        .map(value -> objectMapper.convertValue(value, AgentRunTrace.StepTrace.class))
                        .toList(),
                payload.get("terminalResult") == null
                        ? null : decodeResult(object(payload, "terminalResult")),
                createdAt);
    }

    Map<String, Object> encodeJournal(ToolCallJournalEntry entry) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("arguments", entry.arguments());
        payload.put("argumentsRepaired", entry.argumentsRepaired());
        payload.put("assistantMessage", encodeMessage(entry.assistantMessage()));
        if (entry.observation() != null) {
            payload.put("observation", objectMapper.convertValue(entry.observation(), MAP));
        }
        return payload;
    }

    ToolCallJournalEntry decodeJournal(
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
            String effect,
            String status,
            String argumentsDigest,
            Instant updatedAt,
            Map<String, Object> payload) {
        Object observation = payload.get("observation");
        return new ToolCallJournalEntry(
                schemaVersion,
                sessionId,
                requestId,
                turnId,
                attemptId,
                step,
                callIndex,
                toolCallId,
                toolName,
                toolSchemaVersion,
                AgentTool.Effect.valueOf(effect),
                ToolJournalStatus.valueOf(status),
                map(payload, "arguments"),
                argumentsDigest,
                bool(payload, "argumentsRepaired"),
                (AgentMessage.Assistant) decodeMessage(object(payload, "assistantMessage")),
                observation == null
                        ? null : objectMapper.convertValue(observation, AgentToolObservation.class),
                updatedAt);
    }

    private Map<String, Object> encodeResult(AgentRunResult result) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        encoded.put("state", result.state().name());
        encoded.put("output", result.output());
        encoded.put("clarification", result.clarification());
        encoded.put("fallbackReason", result.fallbackReason());
        encoded.put("cancellationReason", result.cancellationReason());
        encoded.put("degraded", result.degraded());
        encoded.put("messages", result.messages().stream().map(this::encodeMessage).toList());
        encoded.put("trace", objectMapper.convertValue(result.trace(), MAP));
        return encoded;
    }

    private AgentRunResult decodeResult(Map<String, Object> encoded) {
        return new AgentRunResult(
                AgentTerminalState.valueOf(string(encoded, "state")),
                map(encoded, "output"),
                string(encoded, "clarification"),
                string(encoded, "fallbackReason"),
                string(encoded, "cancellationReason"),
                bool(encoded, "degraded"),
                list(encoded, "messages").stream().map(this::decodeMessage).toList(),
                objectMapper.convertValue(required(encoded, "trace"), AgentRunTrace.class));
    }

    private Map<String, Object> encodeMessage(AgentMessage message) {
        WorkspaceMessageCodec.EncodedMessage encoded = messageCodec.encode(message);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("eventType", encoded.eventType());
        result.put("schemaVersion", encoded.schemaVersion());
        result.put("messageId", encoded.messageId());
        result.put("toolCallId", encoded.toolCallId());
        result.put("requestId", encoded.requestId());
        result.put("turnId", encoded.turnId());
        result.put("payload", encoded.payload());
        return result;
    }

    private AgentMessage decodeMessage(Object value) {
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalStateException("checkpoint message is not an object");
        }
        Map<String, Object> encoded = normalize(values);
        return messageCodec.decode(
                string(encoded, "eventType"),
                Math.toIntExact(number(encoded, "schemaVersion")),
                string(encoded, "messageId"),
                string(encoded, "toolCallId"),
                string(encoded, "requestId"),
                string(encoded, "turnId"),
                object(encoded, "payload"));
    }

    private static Object required(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            throw new IllegalStateException("checkpoint field is required: " + key);
        }
        return value;
    }

    private static Map<String, Object> object(Map<String, Object> source, String key) {
        Object value = required(source, key);
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalStateException("checkpoint field is not an object: " + key);
        }
        return normalize(values);
    }

    private static Map<String, Object> map(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalStateException("checkpoint field is not an object: " + key);
        }
        return Map.copyOf(normalize(values));
    }

    private static List<Object> list(Map<String, Object> source, String key) {
        Object value = source.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalStateException("checkpoint field is not an array: " + key);
        }
        return new ArrayList<>(values);
    }

    private static List<String> stringList(Map<String, Object> source, String key) {
        return list(source, key).stream().map(String::valueOf).toList();
    }

    private static Map<String, Object> normalize(Map<?, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String string(Map<String, Object> source, String key) {
        Object value = source.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Map<String, Object> source, String key) {
        Object value = required(source, key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("checkpoint field is not numeric: " + key);
        }
        return number.longValue();
    }

    private static boolean bool(Map<String, Object> source, String key) {
        return Boolean.TRUE.equals(source.get(key));
    }
}
