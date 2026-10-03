package io.seekflux.platform.persistence.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.domain.exception.AgentExecutionFencedException;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Called inside the caller's transaction. The session row is the append linearization lock. */
final class JdbcWorkspaceFacts {
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };
    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final WorkspaceMessageCodec messages = new WorkspaceMessageCodec();

    JdbcWorkspaceFacts(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    long lock(String sessionId, long fence) {
        var head = jdbc.sql("""
                SELECT event_position, active_fencing_token FROM agent.sessions
                WHERE session_id = :sessionId FOR UPDATE
                """).param("sessionId", sessionId)
                .query((row, index) -> new long[]{row.getLong(1), row.getLong(2)}).optional();
        if (head.isEmpty() || head.get()[1] != fence) {
            throw new AgentExecutionFencedException(sessionId, fence);
        }
        return head.get()[0];
    }

    long message(String sessionId, AgentMessage message, Map<String, Object> extra, Instant time) {
        var encoded = messages.encode(message);
        if (message instanceof AgentMessage.ToolResult result) {
            var existing = jdbc.sql("""
                    SELECT message_id FROM agent.workspace_events WHERE session_id = :sessionId
                      AND tool_call_id = :callId AND event_type = 'TOOL_RESULT_MESSAGE'
                    """).param("sessionId", sessionId).param("callId", result.toolCallId()).query(String.class).optional();
            if (existing.isPresent() && !existing.get().equals(message.messageId())) {
                throw new IllegalStateException("a Tool call already has a different terminal result message");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>(encoded.payload());
        payload.putAll(extra);
        return append(sessionId, encoded.eventId(), encoded.eventType(), encoded.schemaVersion(),
                encoded.messageId(), encoded.toolCallId(), encoded.requestId(), encoded.turnId(), payload, time);
    }

    long fact(String sessionId, String identity, String type, String requestId, String turnId,
              Map<String, Object> payload, Instant time) {
        return append(sessionId, UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
                type, 2, null, null, requestId, turnId, payload, time);
    }

    private long append(String sessionId, UUID eventId, String type, int schema,
                        String messageId, String callId, String requestId, String turnId,
                        Map<String, Object> payload, Instant time) {
        String json = json(payload);
        var existing = jdbc.sql("""
                SELECT event_position, payload @> CAST(:payload AS jsonb) AS same
                FROM agent.workspace_events WHERE event_id = :eventId AND session_id = :sessionId
                """).param("eventId", eventId).param("sessionId", sessionId).param("payload", json)
                .query((row, index) -> Map.entry(row.getLong(1), row.getBoolean(2))).optional();
        if (existing.isPresent()) {
            if (!existing.get().getValue()) {
                throw new IllegalStateException("immutable Workspace event identity/content conflict: " + eventId);
            }
            return existing.get().getKey();
        }
        long position = jdbc.sql("""
                UPDATE agent.sessions SET event_position = event_position + 1, version = version + 1,
                    updated_at = :time WHERE session_id = :sessionId RETURNING event_position
                """).param("sessionId", sessionId).param("time", time.atOffset(ZoneOffset.UTC))
                .query(Long.class).single();
        jdbc.sql("""
                INSERT INTO agent.workspace_events(event_id, session_id, event_position, event_type,
                    schema_version, message_id, tool_call_id, request_id, turn_id, event_time, payload)
                VALUES (:eventId, :sessionId, :position, :type, :schema, :messageId, :callId,
                    :requestId, :turnId, :time, CAST(:payload AS jsonb))
                """).param("eventId", eventId).param("sessionId", sessionId).param("position", position)
                .param("type", type).param("schema", schema).param("messageId", messageId)
                .param("callId", callId).param("requestId", requestId).param("turnId", turnId)
                .param("time", time.atOffset(ZoneOffset.UTC)).param("payload", json).update();
        return position;
    }

    Map<String, Object> payload(String sessionId, long position) {
        return parse(jdbc.sql("""
                SELECT payload::text FROM agent.workspace_events
                WHERE session_id = :sessionId AND event_position = :position
                """).param("sessionId", sessionId).param("position", position).query(String.class).single());
    }

    String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("cannot encode Workspace fact", failure); }
    }

    Map<String, Object> parse(String value) {
        try { return mapper.readValue(value, MAP); }
        catch (JsonProcessingException failure) { throw new IllegalStateException("cannot decode Workspace fact", failure); }
    }
}
