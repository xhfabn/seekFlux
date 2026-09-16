package io.seekflux.platform.persistence.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.session.SessionStatePatch;
import io.seekflux.platform.agentruntime.domain.exception.AgentExecutionFencedException;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.exception.AgentSessionStateConflictException;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.QueueCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.QueuedMessageBatch;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentSessionStore implements AgentSessionStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final WorkspaceMessageCodec messageCodec = new WorkspaceMessageCodec();

    public JdbcAgentSessionStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgentSession> restoreFresh(String sessionId) {
        List<WorkspaceEvent> events = jdbcClient.sql("""
                        SELECT event_position, event_type, schema_version, message_id, tool_call_id,
                               request_id, turn_id, event_time, payload::text
                        FROM agent.workspace_events
                        WHERE session_id = :sessionId
                        ORDER BY event_position
                        """)
                .param("sessionId", sessionId)
                .query(this::mapEvent)
                .list();
        return events.isEmpty() ? Optional.empty() : Optional.of(AgentSession.replay(sessionId, events));
    }

    @Override
    @Transactional
    public AgentSession createIfAbsent(String sessionId, AgentDefinition definition, Instant eventTime) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO agent.sessions (
                            session_id, agent_id, agent_version, version, event_position,
                            status, snapshot, created_at, updated_at
                        ) VALUES (
                            :sessionId, :agentId, :agentVersion, 1, 1,
                            'IDLE', '{}'::jsonb, :eventTime, :eventTime
                        )
                        ON CONFLICT (session_id) DO NOTHING
                        """)
                .param("sessionId", sessionId)
                .param("agentId", definition.id())
                .param("agentVersion", definition.version())
                .param("eventTime", databaseTime(eventTime))
                .update();
        if (inserted == 1) {
            insertWorkspaceEvent(
                    sessionId,
                    1,
                    "SESSION_CREATED",
                    null,
                    null,
                    eventTime,
                    Map.of("agentId", definition.id(), "agentVersion", definition.version()));
        }
        AgentSession session = restoreFresh(sessionId)
                .orElseThrow(() -> new IllegalStateException("agent session creation did not produce an event stream"));
        if (!session.agentId().equals(definition.id())) {
            throw new IllegalStateException("existing session belongs to a different agent definition");
        }
        return session;
    }

    @Override
    @Transactional
    public IngressCommitResult commitIngress(
            AgentRunRequest request, long fencingToken, Instant eventTime) {
        boolean duplicate = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM agent.workspace_events
                            WHERE session_id = :sessionId AND request_id = :requestId
                        )
                        """)
                .param("sessionId", request.sessionId())
                .param("requestId", request.requestId())
                .query(Boolean.class)
                .single();
        if (duplicate) {
            boolean recovered = jdbcClient.sql("""
                            UPDATE agent.sessions
                            SET active_fencing_token = :fencingToken,
                                updated_at = :eventTime
                            WHERE session_id = :sessionId
                              AND status = 'EXECUTING'
                              AND active_fencing_token < :fencingToken
                            RETURNING true
                            """)
                    .param("sessionId", request.sessionId())
                    .param("fencingToken", fencingToken)
                    .param("eventTime", databaseTime(eventTime))
                    .query(Boolean.class)
                    .optional()
                    .orElse(false);
            if (recovered) {
                return IngressCommitResult.RECOVERED;
            }
            return IngressCommitResult.DUPLICATE;
        }
        SessionStatePatch statePatch = request.statePatch();
        long position;
        if (statePatch == null) {
            position = advancePosition(request.sessionId(), "EXECUTING", fencingToken, eventTime);
        } else {
            PositionAdvance advanced = advancePositionWithState(
                    request.sessionId(), statePatch, "EXECUTING", fencingToken, eventTime);
            insertWorkspaceEvent(
                    request.sessionId(),
                    advanced.eventPosition() - 1,
                    "STATE_PATCHED",
                    null,
                    null,
                    eventTime,
                    Map.of(
                            "baseVersion", statePatch.baseVersion(),
                            "stateVersion", advanced.stateVersion(),
                            "state", statePatch.state()));
            position = advanced.eventPosition();
        }
        String messageId = deterministicMessageId(
                request.sessionId(), request.requestId(), request.turnId(), "user");
        insertWorkspaceEvent(
                eventId(messageId),
                request.sessionId(),
                position,
                "USER_MESSAGE",
                1,
                messageId,
                null,
                request.requestId(),
                request.turnId(),
                eventTime,
                Map.of("text", request.input()));
        return IngressCommitResult.COMMITTED;
    }

    @Override
    @Transactional
    public QueueCommitResult enqueue(
            AgentRunRequest request, int maxQueueDepth, Instant eventTime) {
        return enqueue(request, Map.of(), maxQueueDepth, eventTime);
    }

    @Override
    @Transactional
    public QueueCommitResult enqueue(
            AgentRunRequest request,
            Map<String, Object> persistentFeatures,
            int maxQueueDepth,
            Instant eventTime) {
        Long currentPosition = jdbcClient.sql("""
                        SELECT event_position
                        FROM agent.sessions
                        WHERE session_id = :sessionId
                        FOR UPDATE
                        """)
                .param("sessionId", request.sessionId())
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("agent session does not exist"));
        int queueDepth = pendingQueueDepth(request.sessionId());
        boolean consumed = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM agent.workspace_events
                            WHERE session_id = :sessionId
                              AND request_id = :requestId
                              AND event_type = 'USER_MESSAGE'
                        )
                        """)
                .param("sessionId", request.sessionId())
                .param("requestId", request.requestId())
                .query(Boolean.class)
                .single();
        if (consumed) {
            return QueueCommitResult.duplicateConsumed(queueDepth);
        }
        boolean pendingDuplicate = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM agent.workspace_events
                            WHERE session_id = :sessionId
                              AND request_id = :requestId
                              AND event_type = 'QUEUED_USER_MESSAGE'
                        )
                        """)
                .param("sessionId", request.sessionId())
                .param("requestId", request.requestId())
                .query(Boolean.class)
                .single();
        if (pendingDuplicate) {
            return QueueCommitResult.duplicatePending(queueDepth);
        }
        if (queueDepth >= maxQueueDepth) {
            return QueueCommitResult.full(queueDepth);
        }

        long position = currentPosition + 1;
        int updated = jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET event_position = :position,
                            version = version + 1,
                            updated_at = :eventTime
                        WHERE session_id = :sessionId
                        """)
                .param("position", position)
                .param("eventTime", databaseTime(eventTime))
                .param("sessionId", request.sessionId())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("queued message did not advance the session");
        }
        String messageId = deterministicMessageId(
                request.sessionId(), request.requestId(), request.turnId(), "user");
        Map<String, Object> payload = queuedPayload(request, persistentFeatures);
        insertWorkspaceEvent(
                eventId("queued:" + messageId),
                request.sessionId(),
                position,
                "QUEUED_USER_MESSAGE",
                1,
                messageId,
                null,
                request.requestId(),
                request.turnId(),
                eventTime,
                payload);
        return QueueCommitResult.committed(queueDepth + 1);
    }

    @Override
    @Transactional
    public Optional<QueuedMessageBatch> promoteQueued(
            String sessionId, long fencingToken, Instant eventTime) {
        SessionHead head = jdbcClient.sql("""
                        SELECT event_position, state_version, active_fencing_token
                        FROM agent.sessions
                        WHERE session_id = :sessionId
                        FOR UPDATE
                        """)
                .param("sessionId", sessionId)
                .query((row, rowNumber) -> new SessionHead(
                        row.getLong("event_position"),
                        row.getLong("state_version"),
                        row.getLong("active_fencing_token")))
                .optional()
                .orElseThrow(() -> new IllegalStateException("agent session does not exist"));
        if (head.fencingToken() != fencingToken) {
            throw new AgentExecutionFencedException(sessionId, fencingToken);
        }
        List<WorkspaceEvent.QueuedUserMessage> queued = jdbcClient.sql("""
                        SELECT q.event_position, q.event_type, q.schema_version, q.message_id,
                               q.tool_call_id, q.request_id, q.turn_id, q.event_time, q.payload::text
                        FROM agent.workspace_events q
                        WHERE q.session_id = :sessionId
                          AND q.event_type = 'QUEUED_USER_MESSAGE'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM agent.workspace_events promoted
                              WHERE promoted.session_id = q.session_id
                                AND promoted.event_type = 'USER_MESSAGE'
                                AND promoted.message_id = q.message_id
                          )
                        ORDER BY q.event_position
                        """)
                .param("sessionId", sessionId)
                .query((row, rowNumber) ->
                        (WorkspaceEvent.QueuedUserMessage) mapEvent(row, rowNumber))
                .list();
        if (queued.isEmpty()) {
            return Optional.empty();
        }

        SessionStatePatch finalPatch = queued.getLast().request().statePatch();
        int eventCount = queued.size() + (finalPatch == null ? 0 : 1);
        long finalPosition = head.eventPosition() + eventCount;
        long finalStateVersion = head.stateVersion() + (finalPatch == null ? 0 : 1);
        String snapshot = finalPatch == null ? null : toJson(finalPatch.state());
        int advanced = finalPatch == null
                ? jdbcClient.sql("""
                                UPDATE agent.sessions
                                SET event_position = :eventPosition,
                                    version = version + :eventCount,
                                    status = 'EXECUTING',
                                    updated_at = :eventTime
                                WHERE session_id = :sessionId
                                  AND active_fencing_token = :fencingToken
                                """)
                        .param("eventPosition", finalPosition)
                        .param("eventCount", eventCount)
                        .param("eventTime", databaseTime(eventTime))
                        .param("sessionId", sessionId)
                        .param("fencingToken", fencingToken)
                        .update()
                : jdbcClient.sql("""
                                UPDATE agent.sessions
                                SET event_position = :eventPosition,
                                    version = version + :eventCount,
                                    state_version = :stateVersion,
                                    snapshot = CAST(:snapshot AS jsonb),
                                    status = 'EXECUTING',
                                    updated_at = :eventTime
                                WHERE session_id = :sessionId
                                  AND active_fencing_token = :fencingToken
                                """)
                        .param("eventPosition", finalPosition)
                        .param("eventCount", eventCount)
                        .param("stateVersion", finalStateVersion)
                        .param("snapshot", snapshot)
                        .param("eventTime", databaseTime(eventTime))
                        .param("sessionId", sessionId)
                        .param("fencingToken", fencingToken)
                        .update();
        if (advanced != 1) {
            throw new AgentExecutionFencedException(sessionId, fencingToken);
        }

        long position = head.eventPosition() + 1;
        if (finalPatch != null) {
            insertWorkspaceEvent(
                    sessionId,
                    position++,
                    "STATE_PATCHED",
                    null,
                    null,
                    eventTime,
                    Map.of(
                            "baseVersion", head.stateVersion(),
                            "stateVersion", finalStateVersion,
                            "state", finalPatch.state()));
        }
        for (WorkspaceEvent.QueuedUserMessage message : queued) {
            AgentRunRequest request = message.request();
            insertWorkspaceEvent(
                    eventId(message.messageId()),
                    sessionId,
                    position++,
                    "USER_MESSAGE",
                    message.schemaVersion(),
                    message.messageId(),
                    null,
                    request.requestId(),
                    request.turnId(),
                    eventTime,
                    Map.of("text", request.input()));
        }
        return Optional.of(new QueuedMessageBatch(queued));
    }

    @Override
    @Transactional
    public void appendOutcome(
            String sessionId, AgentRunResult result, long fencingToken, Instant eventTime) {
        String eventType = switch (result.state()) {
            case CANCELLED -> "RUN_CANCELLED";
            case FAILED -> "RUN_FAILED";
            default -> "RUN_COMPLETED";
        };
        long position = advanceOutcomePosition(
                sessionId, result, fencingToken, eventTime, result.messages().size() + 1);
        long messagePosition = position - result.messages().size();
        for (AgentMessage message : result.messages()) {
            insertMessageEvent(sessionId, messagePosition++, message, eventTime);
        }
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("agentRunId", result.trace().agentRunId());
        payload.put("state", result.state().name());
        String terminalReason = result.state() == AgentTerminalState.CANCELLED
                ? result.cancellationReason()
                : result.fallbackReason();
        if (terminalReason != null) {
            payload.put("reason", terminalReason);
        }
        insertWorkspaceEvent(sessionId, position, eventType, null, null, eventTime, payload);
        insertOutcomeOutbox(sessionId, position, result, fencingToken, eventTime);
        clearRecoveryState(sessionId, result.trace().requestId(), fencingToken);
    }

    private long advancePosition(
            String sessionId, String status, long fencingToken, Instant eventTime) {
        return jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET event_position = event_position + 1,
                            version = version + 1,
                            active_fencing_token = :fencingToken,
                            status = :status,
                            updated_at = :eventTime
                        WHERE session_id = :sessionId
                          AND active_fencing_token <= :fencingToken
                        RETURNING event_position
                        """)
                .param("status", status)
                .param("eventTime", databaseTime(eventTime))
                .param("sessionId", sessionId)
                .param("fencingToken", fencingToken)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new AgentExecutionFencedException(sessionId, fencingToken));
    }

    private PositionAdvance advancePositionWithState(
            String sessionId,
            SessionStatePatch patch,
            String status,
            long fencingToken,
            Instant eventTime) {
        Optional<PositionAdvance> advanced = jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET event_position = event_position + 2,
                            version = version + 2,
                            state_version = state_version + 1,
                            active_fencing_token = :fencingToken,
                            snapshot = CAST(:snapshot AS jsonb),
                            status = :status,
                            updated_at = :eventTime
                        WHERE session_id = :sessionId
                          AND state_version = :baseVersion
                          AND active_fencing_token <= :fencingToken
                        RETURNING event_position, state_version
                        """)
                .param("snapshot", toJson(patch.state()))
                .param("status", status)
                .param("eventTime", databaseTime(eventTime))
                .param("sessionId", sessionId)
                .param("baseVersion", patch.baseVersion())
                .param("fencingToken", fencingToken)
                .query((row, rowNumber) -> new PositionAdvance(
                        row.getLong("event_position"),
                        row.getLong("state_version")))
                .optional();
        if (advanced.isPresent()) {
            return advanced.get();
        }
        long activeToken = activeFencingToken(sessionId);
        if (activeToken > fencingToken) {
            throw new AgentExecutionFencedException(sessionId, fencingToken);
        }
        throw new AgentSessionStateConflictException(patch.baseVersion());
    }

    private long advanceOutcomePosition(
            String sessionId,
            AgentRunResult result,
            long fencingToken,
            Instant eventTime,
            int eventCount) {
        return jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET event_position = event_position + :eventCount,
                            version = version + :eventCount,
                            status = :status,
                            last_agent_run_id = :agentRunId,
                            last_turn_id = :turnId,
                            updated_at = :eventTime
                        WHERE session_id = :sessionId
                          AND active_fencing_token = :fencingToken
                        RETURNING event_position
                        """)
                .param("agentRunId", UUID.fromString(result.trace().agentRunId()))
                .param("turnId", result.trace().turnId())
                .param("eventCount", eventCount)
                .param("status", result.state() == AgentTerminalState.NEED_CLARIFICATION
                        ? "SUSPENDED"
                        : "COMPLETED")
                .param("eventTime", databaseTime(eventTime))
                .param("sessionId", sessionId)
                .param("fencingToken", fencingToken)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new AgentExecutionFencedException(sessionId, fencingToken));
    }

    private long activeFencingToken(String sessionId) {
        return jdbcClient.sql("""
                        SELECT active_fencing_token
                        FROM agent.sessions
                        WHERE session_id = :sessionId
                        """)
                .param("sessionId", sessionId)
                .query(Long.class)
                .single();
    }

    private void insertOutcomeOutbox(
            String sessionId,
            long position,
            AgentRunResult result,
            long fencingToken,
            Instant eventTime) {
        String eventType = switch (result.state()) {
            case FALLBACK_REQUIRED -> "agent.run.fallback.v1";
            case CANCELLED -> "agent.run.cancelled.v1";
            case FAILED -> "agent.run.failed.v1";
            default -> "agent.run.completed.v1";
        };
        UUID eventId = UUID.nameUUIDFromBytes(
                ("agent-outcome:" + sessionId + ":" + position).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        payload.put("agentRunId", result.trace().agentRunId());
        payload.put("requestId", result.trace().requestId());
        payload.put("turnId", result.trace().turnId());
        payload.put("state", result.state().name());
        payload.put("executionMode", result.trace().executionMode());
        payload.put("fallbackReason", result.fallbackReason());
        payload.put("cancellationReason", result.cancellationReason());
        payload.put("messageCount", result.messages().size());
        payload.put("fencingToken", fencingToken);
        payload.put("definition", result.trace().definition());
        payload.put("tookMillis", result.trace().tookMillis());
        jdbcClient.sql("""
                        INSERT INTO outbox.events (
                            event_id, aggregate_type, aggregate_id, event_type,
                            schema_version, event_time, payload
                        ) VALUES (
                            :eventId, 'AGENT_SESSION', :aggregateId, :eventType,
                            1, :eventTime, CAST(:payload AS jsonb)
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("aggregateId", sessionId)
                .param("eventType", eventType)
                .param("eventTime", databaseTime(eventTime))
                .param("payload", toJson(payload))
                .update();
    }

    private void clearRecoveryState(String sessionId, String requestId, long fencingToken) {
        int authorized = jdbcClient.sql("""
                        SELECT count(*)
                        FROM agent.sessions
                        WHERE session_id = :sessionId
                          AND active_fencing_token = :fencingToken
                        """)
                .param("sessionId", sessionId)
                .param("fencingToken", fencingToken)
                .query(Integer.class)
                .single();
        if (authorized != 1) {
            throw new AgentExecutionFencedException(sessionId, fencingToken);
        }
        jdbcClient.sql("""
                        DELETE FROM agent.tool_call_journal
                        WHERE session_id = :sessionId AND request_id = :requestId
                        """)
                .param("sessionId", sessionId)
                .param("requestId", requestId)
                .update();
        jdbcClient.sql("""
                        DELETE FROM agent.runtime_checkpoints
                        WHERE session_id = :sessionId AND request_id = :requestId
                        """)
                .param("sessionId", sessionId)
                .param("requestId", requestId)
                .update();
    }

    private void insertWorkspaceEvent(
            String sessionId,
            long position,
            String eventType,
            String requestId,
            String turnId,
            Instant eventTime,
            Map<String, Object> payload) {
        insertWorkspaceEvent(
                UUID.randomUUID(),
                sessionId,
                position,
                eventType,
                1,
                null,
                null,
                requestId,
                turnId,
                eventTime,
                payload);
    }

    private void insertMessageEvent(
            String sessionId,
            long position,
            AgentMessage message,
            Instant eventTime) {
        WorkspaceMessageCodec.EncodedMessage encoded = messageCodec.encode(message);
        insertWorkspaceEvent(
                encoded.eventId(),
                sessionId,
                position,
                encoded.eventType(),
                encoded.schemaVersion(),
                encoded.messageId(),
                encoded.toolCallId(),
                encoded.requestId(),
                encoded.turnId(),
                eventTime,
                encoded.payload());
    }

    private void insertWorkspaceEvent(
            UUID eventId,
            String sessionId,
            long position,
            String eventType,
            int schemaVersion,
            String messageId,
            String toolCallId,
            String requestId,
            String turnId,
            Instant eventTime,
            Map<String, Object> payload) {
        int rows = jdbcClient.sql("""
                        INSERT INTO agent.workspace_events (
                            event_id, session_id, event_position, event_type, schema_version,
                            message_id, tool_call_id, request_id, turn_id, event_time, payload
                        ) VALUES (
                            :eventId, :sessionId, :eventPosition, :eventType, :schemaVersion,
                            :messageId, :toolCallId, :requestId, :turnId, :eventTime, CAST(:payload AS jsonb)
                        )
                        """)
                .param("eventId", eventId)
                .param("sessionId", sessionId)
                .param("eventPosition", position)
                .param("eventType", eventType)
                .param("schemaVersion", schemaVersion)
                .param("messageId", messageId, java.sql.Types.VARCHAR)
                .param("toolCallId", toolCallId, java.sql.Types.VARCHAR)
                .param("requestId", requestId, java.sql.Types.VARCHAR)
                .param("turnId", turnId, java.sql.Types.VARCHAR)
                .param("eventTime", databaseTime(eventTime))
                .param("payload", toJson(payload))
                .update();
        if (rows != 1) {
            throw new IllegalStateException("workspace event insert did not affect exactly one row");
        }
    }

    private static UUID eventId(String messageId) {
        try {
            return UUID.fromString(messageId);
        } catch (IllegalArgumentException invalidUuid) {
            return UUID.nameUUIDFromBytes(messageId.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String deterministicMessageId(
            String sessionId,
            String requestId,
            String turnId,
            String kind) {
        String identity = sessionId + ":" + requestId + ":" + turnId + ":" + kind;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private WorkspaceEvent mapEvent(ResultSet row, int rowNumber) throws SQLException {
        long position = row.getLong("event_position");
        Instant eventTime = row.getObject("event_time", OffsetDateTime.class).toInstant();
        Map<String, Object> payload = fromJson(row.getString("payload"));
        String agentRunId = string(payload, "agentRunId");
        String reason = string(payload, "reason");
        int schemaVersion = row.getInt("schema_version");
        return switch (row.getString("event_type")) {
            case "SESSION_CREATED" -> new WorkspaceEvent.SessionCreated(
                    position,
                    eventTime,
                    string(payload, "agentId"),
                    string(payload, "agentVersion"));
            case "USER_MESSAGE" -> new WorkspaceEvent.UserMessage(
                    position,
                    eventTime,
                    schemaVersion,
                    row.getString("message_id"),
                    row.getString("request_id"),
                    row.getString("turn_id"),
                    string(payload, "text"));
            case "QUEUED_USER_MESSAGE" -> new WorkspaceEvent.QueuedUserMessage(
                    position,
                    eventTime,
                    schemaVersion,
                    row.getString("message_id"),
                    queuedRequest(
                            row.getString("request_id"),
                            row.getString("turn_id"),
                            string(payload, "text"),
                            payload),
                    queuedFeatures(payload));
            case "ASSISTANT_MESSAGE" -> new WorkspaceEvent.AssistantMessage(
                    position,
                    eventTime,
                    (AgentMessage.Assistant) messageCodec.decode(
                            "ASSISTANT_MESSAGE",
                            schemaVersion,
                            row.getString("message_id"),
                            null,
                            row.getString("request_id"),
                            row.getString("turn_id"),
                            payload));
            case "TOOL_RESULT_MESSAGE" -> new WorkspaceEvent.ToolResultMessage(
                    position,
                    eventTime,
                    (AgentMessage.ToolResult) messageCodec.decode(
                            "TOOL_RESULT_MESSAGE",
                            schemaVersion,
                            row.getString("message_id"),
                            row.getString("tool_call_id"),
                            row.getString("request_id"),
                            row.getString("turn_id"),
                            payload));
            case "STATE_PATCHED" -> new WorkspaceEvent.StatePatched(
                    position,
                    eventTime,
                    number(payload, "baseVersion"),
                    number(payload, "stateVersion"),
                    map(payload, "state"));
            case "RUN_COMPLETED" -> new WorkspaceEvent.RunCompleted(
                    position,
                    eventTime,
                    agentRunId,
                    AgentTerminalState.valueOf(string(payload, "state")),
                    reason);
            case "RUN_CANCELLED" -> new WorkspaceEvent.RunCancelled(
                    position, eventTime, agentRunId, reason);
            case "RUN_FAILED" -> new WorkspaceEvent.RunFailed(
                    position, eventTime, agentRunId, reason);
            default -> throw new IllegalStateException("unknown workspace event type: " + row.getString("event_type"));
        };
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize workspace event", exception);
        }
    }

    private Map<String, Object> fromJson(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize workspace event", exception);
        }
    }

    private static String string(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static long number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("workspace event field is not numeric: " + key);
        }
        return number.longValue();
    }

    private static Map<String, Object> map(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalStateException("workspace event field is not an object: " + key);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        values.forEach((itemKey, itemValue) -> result.put(String.valueOf(itemKey), itemValue));
        return Map.copyOf(result);
    }

    private int pendingQueueDepth(String sessionId) {
        return jdbcClient.sql("""
                        SELECT count(*)
                        FROM agent.workspace_events q
                        WHERE q.session_id = :sessionId
                          AND q.event_type = 'QUEUED_USER_MESSAGE'
                          AND NOT EXISTS (
                              SELECT 1
                              FROM agent.workspace_events promoted
                              WHERE promoted.session_id = q.session_id
                                AND promoted.event_type = 'USER_MESSAGE'
                                AND promoted.message_id = q.message_id
                          )
                        """)
                .param("sessionId", sessionId)
                .query(Integer.class)
                .single();
    }

    static Map<String, Object> queuedPayload(AgentRunRequest request) {
        return queuedPayload(request, Map.of());
    }

    static Map<String, Object> queuedPayload(
            AgentRunRequest request,
            Map<String, Object> persistentFeatures) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("sessionId", request.sessionId());
        payload.put("text", request.input());
        payload.put("attributes", request.attributes());
        payload.put("persistentFeatures",
                persistentFeatures == null ? Map.of() : Map.copyOf(persistentFeatures));
        payload.put("ingressMode", request.ingressMode().name());
        if (request.statePatch() != null) {
            payload.put("statePatch", Map.of(
                    "baseVersion", request.statePatch().baseVersion(),
                    "state", request.statePatch().state()));
        }
        return Map.copyOf(payload);
    }

    static Map<String, Object> queuedFeatures(Map<String, Object> payload) {
        Map<String, Object> features = nullableMap(payload, "persistentFeatures");
        return features == null ? Map.of() : features;
    }

    static AgentRunRequest queuedRequest(
            String requestId,
            String turnId,
            String text,
            Map<String, Object> payload) {
        Map<String, Object> patch = nullableMap(payload, "statePatch");
        SessionStatePatch statePatch = patch == null
                ? null
                : new SessionStatePatch(number(patch, "baseVersion"), map(patch, "state"));
        String ingress = string(payload, "ingressMode");
        return new AgentRunRequest(
                requestId,
                string(payload, "sessionId") == null ? "" : string(payload, "sessionId"),
                turnId,
                text,
                nullableMap(payload, "attributes"),
                statePatch,
                ingress == null
                        ? io.seekflux.platform.agentruntime.application.command.AgentIngressMode.STEER
                        : io.seekflux.platform.agentruntime.application.command.AgentIngressMode.valueOf(ingress));
    }

    private static Map<String, Object> nullableMap(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalStateException("workspace event field is not an object: " + key);
        }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        values.forEach((itemKey, itemValue) -> result.put(String.valueOf(itemKey), itemValue));
        return Map.copyOf(result);
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private record PositionAdvance(long eventPosition, long stateVersion) {
    }

    private record SessionHead(long eventPosition, long stateVersion, long fencingToken) {
    }
}
