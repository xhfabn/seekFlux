package io.seekflux.platform.persistence.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.domain.exception.AgentExecutionFencedException;
import io.seekflux.platform.agentruntime.domain.model.recovery.CheckpointBoundary;
import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeAction;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeIngress;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolJournalStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentRecoveryStore implements AgentRecoveryStore {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final AgentRecoveryCodec codec;

    public JdbcAgentRecoveryStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.codec = new AgentRecoveryCodec(objectMapper);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    @Transactional
    public RecoveryPlan commitResume(
            ResumeIngress ingress, long fencingToken, Instant eventTime) {
        assertAuthority(ingress.sessionId(), fencingToken);
        jdbcClient.sql("""
                        UPDATE agent.tool_call_journal
                        SET status = 'UNKNOWN',
                            fencing_token = :fencingToken,
                            updated_at = :eventTime
                        WHERE session_id = :sessionId
                          AND request_id = :requestId
                          AND status = 'EXECUTING'
                        """)
                .param("sessionId", ingress.sessionId())
                .param("requestId", ingress.requestId())
                .param("fencingToken", fencingToken)
                .param("eventTime", databaseTime(eventTime))
                .update();

        Optional<RuntimeCheckpoint> checkpoint = jdbcClient.sql("""
                        SELECT checkpoint_id, schema_version, session_id, request_id, turn_id,
                               attempt_id, boundary, fencing_token, message_cutoff, next_step,
                               tool_call_count, remaining_budget_ms, created_at, payload::text
                        FROM agent.runtime_checkpoints
                        WHERE session_id = :sessionId AND request_id = :requestId
                        """)
                .param("sessionId", ingress.sessionId())
                .param("requestId", ingress.requestId())
                .query(this::mapCheckpoint)
                .optional();
        if (checkpoint.isEmpty()) {
            return RecoveryPlan.START_NEW;
        }
        RuntimeCheckpoint restored = checkpoint.get();
        if (!restored.turnId().equals(ingress.turnId())) {
            throw new IllegalStateException("checkpoint turn does not match resume ingress");
        }
        List<ToolCallJournalEntry> calls = jdbcClient.sql("""
                        SELECT schema_version, session_id, request_id, turn_id, attempt_id,
                               step, call_index, tool_call_id, tool_name, tool_schema_version,
                               effect, status, arguments_digest, updated_at, payload::text
                        FROM agent.tool_call_journal
                        WHERE session_id = :sessionId AND request_id = :requestId
                        ORDER BY step, call_index
                        """)
                .param("sessionId", ingress.sessionId())
                .param("requestId", ingress.requestId())
                .query(this::mapJournal)
                .list();

        if (restored.terminal()) {
            return new RecoveryPlan(ResumeAction.COMMIT_TERMINAL, restored, calls);
        }
        if (restored.boundary() == CheckpointBoundary.POST_TURN) {
            return new RecoveryPlan(ResumeAction.RESUME_POST_TURN, restored, List.of());
        }
        List<ToolCallJournalEntry> currentStepCalls = calls.stream()
                .filter(call -> call.step() == restored.nextStep())
                .toList();
        boolean unsafeUnknown = currentStepCalls.stream().anyMatch(call ->
                call.status() == ToolJournalStatus.UNKNOWN && !call.safeToRetry());
        if (unsafeUnknown) {
            return new RecoveryPlan(
                    ResumeAction.FAIL_UNSAFE_PENDING_TOOL, restored, currentStepCalls);
        }
        if (!currentStepCalls.isEmpty()) {
            return new RecoveryPlan(
                    ResumeAction.RESUME_PENDING_TOOLS, restored, currentStepCalls);
        }
        return new RecoveryPlan(ResumeAction.RESUME_PRE_TURN, restored, List.of());
    }

    @Override
    @Transactional
    public void saveCheckpoint(
            RuntimeCheckpoint checkpoint, long fencingToken, Instant eventTime) {
        String payload = toJson(codec.encodeCheckpoint(checkpoint));
        int rows = jdbcClient.sql("""
                        INSERT INTO agent.runtime_checkpoints (
                            checkpoint_id, schema_version, session_id, request_id, turn_id,
                            attempt_id, boundary, fencing_token, message_cutoff, next_step,
                            tool_call_count, remaining_budget_ms, payload, created_at, updated_at
                        )
                        SELECT :checkpointId, :schemaVersion, :sessionId, :requestId, :turnId,
                               :attemptId, :boundary, :fencingToken, :messageCutoff, :nextStep,
                               :toolCallCount, :remainingBudgetMillis, CAST(:payload AS jsonb),
                               :createdAt, :eventTime
                        FROM agent.sessions
                        WHERE session_id = :sessionId
                          AND active_fencing_token = :fencingToken
                        ON CONFLICT (session_id, request_id) DO UPDATE SET
                            checkpoint_id = EXCLUDED.checkpoint_id,
                            schema_version = EXCLUDED.schema_version,
                            turn_id = EXCLUDED.turn_id,
                            attempt_id = EXCLUDED.attempt_id,
                            boundary = EXCLUDED.boundary,
                            fencing_token = EXCLUDED.fencing_token,
                            message_cutoff = EXCLUDED.message_cutoff,
                            next_step = EXCLUDED.next_step,
                            tool_call_count = EXCLUDED.tool_call_count,
                            remaining_budget_ms = EXCLUDED.remaining_budget_ms,
                            payload = EXCLUDED.payload,
                            created_at = EXCLUDED.created_at,
                            updated_at = EXCLUDED.updated_at
                        WHERE agent.runtime_checkpoints.fencing_token <= EXCLUDED.fencing_token
                        """)
                .param("checkpointId", UUID.fromString(checkpoint.checkpointId()))
                .param("schemaVersion", checkpoint.schemaVersion())
                .param("sessionId", checkpoint.sessionId())
                .param("requestId", checkpoint.requestId())
                .param("turnId", checkpoint.turnId())
                .param("attemptId", UUID.fromString(checkpoint.attemptId()))
                .param("boundary", checkpoint.boundary().name())
                .param("fencingToken", fencingToken)
                .param("messageCutoff", checkpoint.messageCutoff())
                .param("nextStep", checkpoint.nextStep())
                .param("toolCallCount", checkpoint.toolCallCount())
                .param("remainingBudgetMillis", checkpoint.remainingBudgetMillis())
                .param("payload", payload)
                .param("createdAt", databaseTime(checkpoint.createdAt()))
                .param("eventTime", databaseTime(eventTime))
                .update();
        if (rows != 1) {
            throw new AgentExecutionFencedException(checkpoint.sessionId(), fencingToken);
        }
    }

    @Override
    @Transactional
    public void recordToolDecision(
            RuntimeCheckpoint checkpoint,
            List<ToolCallJournalEntry> calls,
            long fencingToken,
            Instant eventTime) {
        saveCheckpoint(checkpoint, fencingToken, eventTime);
        for (ToolCallJournalEntry call : calls) {
            int rows = jdbcClient.sql("""
                            INSERT INTO agent.tool_call_journal (
                                tool_call_id, schema_version, session_id, request_id, turn_id,
                                attempt_id, step, call_index, tool_name, tool_schema_version,
                                effect, status, arguments_digest, fencing_token, payload,
                                created_at, updated_at
                            )
                            SELECT :toolCallId, :schemaVersion, :sessionId, :requestId, :turnId,
                                   :attemptId, :step, :callIndex, :toolName, :toolSchemaVersion,
                                   :effect, 'DECIDED', :argumentsDigest, :fencingToken,
                                   CAST(:payload AS jsonb), :eventTime, :eventTime
                            FROM agent.sessions
                            WHERE session_id = :sessionId
                              AND active_fencing_token = :fencingToken
                            ON CONFLICT (tool_call_id) DO NOTHING
                            """)
                    .param("toolCallId", call.toolCallId())
                    .param("schemaVersion", call.schemaVersion())
                    .param("sessionId", call.sessionId())
                    .param("requestId", call.requestId())
                    .param("turnId", call.turnId())
                    .param("attemptId", UUID.fromString(call.attemptId()))
                    .param("step", call.step())
                    .param("callIndex", call.callIndex())
                    .param("toolName", call.toolName())
                    .param("toolSchemaVersion", call.toolSchemaVersion())
                    .param("effect", call.effect().name())
                    .param("argumentsDigest", call.argumentsDigest())
                    .param("fencingToken", fencingToken)
                    .param("payload", toJson(codec.encodeJournal(call)))
                    .param("eventTime", databaseTime(eventTime))
                    .update();
            if (rows != 1) {
                assertExistingDecision(call, fencingToken);
            }
        }
    }

    @Override
    @Transactional
    public void markToolExecuting(
            String sessionId,
            String requestId,
            String attemptId,
            List<String> toolCallIds,
            long fencingToken,
            Instant eventTime) {
        assertAuthority(sessionId, fencingToken);
        for (String toolCallId : toolCallIds) {
            int rows = jdbcClient.sql("""
                            UPDATE agent.tool_call_journal
                            SET status = 'EXECUTING',
                                attempt_id = :attemptId,
                                fencing_token = :fencingToken,
                                updated_at = :eventTime
                            WHERE tool_call_id = :toolCallId
                              AND session_id = :sessionId
                              AND request_id = :requestId
                              AND status IN ('DECIDED', 'UNKNOWN')
                            """)
                    .param("toolCallId", toolCallId)
                    .param("sessionId", sessionId)
                    .param("requestId", requestId)
                    .param("attemptId", UUID.fromString(attemptId))
                    .param("fencingToken", fencingToken)
                    .param("eventTime", databaseTime(eventTime))
                    .update();
            if (rows != 1) {
                boolean alreadyExecuting = jdbcClient.sql("""
                                SELECT EXISTS (
                                    SELECT 1 FROM agent.tool_call_journal journal
                                    JOIN agent.sessions session ON session.session_id = journal.session_id
                                    WHERE journal.tool_call_id = :toolCallId
                                      AND journal.session_id = :sessionId
                                      AND journal.request_id = :requestId
                                      AND journal.attempt_id = :attemptId
                                      AND journal.status = 'EXECUTING'
                                      AND session.active_fencing_token = :fencingToken
                                )
                                """)
                        .param("toolCallId", toolCallId)
                        .param("sessionId", sessionId)
                        .param("requestId", requestId)
                        .param("attemptId", UUID.fromString(attemptId))
                        .param("fencingToken", fencingToken)
                        .query(Boolean.class)
                        .single();
                if (!alreadyExecuting) {
                    throw new IllegalStateException(
                            "Tool journal was not dispatchable: " + toolCallId);
                }
            }
        }
    }

    @Override
    @Transactional
    public void recordToolResult(
            ToolCallJournalEntry call, long fencingToken, Instant eventTime) {
        if (!call.status().terminal()) {
            throw new IllegalArgumentException("Tool result journal state must be terminal");
        }
        String payload = toJson(codec.encodeJournal(call));
        int rows = jdbcClient.sql("""
                        UPDATE agent.tool_call_journal journal
                        SET status = :status,
                            attempt_id = :attemptId,
                            fencing_token = :fencingToken,
                            payload = CAST(:payload AS jsonb),
                            updated_at = :eventTime
                        WHERE journal.tool_call_id = :toolCallId
                          AND journal.session_id = :sessionId
                          AND journal.request_id = :requestId
                          AND journal.status IN ('DECIDED', 'EXECUTING', 'UNKNOWN')
                          AND EXISTS (
                              SELECT 1 FROM agent.sessions session
                              WHERE session.session_id = journal.session_id
                                AND session.active_fencing_token = :fencingToken
                          )
                        """)
                .param("status", call.status().name())
                .param("attemptId", UUID.fromString(call.attemptId()))
                .param("fencingToken", fencingToken)
                .param("payload", payload)
                .param("eventTime", databaseTime(eventTime))
                .param("toolCallId", call.toolCallId())
                .param("sessionId", call.sessionId())
                .param("requestId", call.requestId())
                .update();
        if (rows != 1) {
            boolean alreadyRecorded = jdbcClient.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM agent.tool_call_journal journal
                                JOIN agent.sessions session ON session.session_id = journal.session_id
                                WHERE journal.tool_call_id = :toolCallId
                                  AND journal.session_id = :sessionId
                                  AND journal.request_id = :requestId
                                  AND journal.attempt_id = :attemptId
                                  AND journal.status = :status
                                  AND journal.arguments_digest = :argumentsDigest
                                  AND journal.payload = CAST(:payload AS jsonb)
                                  AND session.active_fencing_token = :fencingToken
                            )
                            """)
                    .param("toolCallId", call.toolCallId())
                    .param("sessionId", call.sessionId())
                    .param("requestId", call.requestId())
                    .param("attemptId", UUID.fromString(call.attemptId()))
                    .param("status", call.status().name())
                    .param("argumentsDigest", call.argumentsDigest())
                    .param("payload", payload)
                    .param("fencingToken", fencingToken)
                    .query(Boolean.class)
                    .single();
            if (!alreadyRecorded) {
                throw new AgentExecutionFencedException(call.sessionId(), fencingToken);
            }
        }
    }

    private void assertExistingDecision(ToolCallJournalEntry call, long fencingToken) {
        boolean same = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM agent.tool_call_journal journal
                            JOIN agent.sessions session ON session.session_id = journal.session_id
                            WHERE journal.tool_call_id = :toolCallId
                              AND journal.session_id = :sessionId
                              AND journal.request_id = :requestId
                              AND journal.arguments_digest = :argumentsDigest
                              AND session.active_fencing_token = :fencingToken
                        )
                        """)
                .param("toolCallId", call.toolCallId())
                .param("sessionId", call.sessionId())
                .param("requestId", call.requestId())
                .param("argumentsDigest", call.argumentsDigest())
                .param("fencingToken", fencingToken)
                .query(Boolean.class)
                .single();
        if (!same) {
            throw new AgentExecutionFencedException(call.sessionId(), fencingToken);
        }
    }

    private void assertAuthority(String sessionId, long fencingToken) {
        boolean held = jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM agent.sessions
                            WHERE session_id = :sessionId
                              AND active_fencing_token = :fencingToken
                              AND status = 'EXECUTING'
                        )
                        """)
                .param("sessionId", sessionId)
                .param("fencingToken", fencingToken)
                .query(Boolean.class)
                .single();
        if (!held) {
            throw new AgentExecutionFencedException(sessionId, fencingToken);
        }
    }

    private RuntimeCheckpoint mapCheckpoint(ResultSet row, int rowNumber) throws SQLException {
        return codec.decodeCheckpoint(
                row.getInt("schema_version"),
                row.getObject("checkpoint_id", UUID.class).toString(),
                row.getString("boundary"),
                row.getString("session_id"),
                row.getString("request_id"),
                row.getString("turn_id"),
                row.getObject("attempt_id", UUID.class).toString(),
                row.getLong("fencing_token"),
                row.getLong("message_cutoff"),
                row.getInt("next_step"),
                row.getInt("tool_call_count"),
                row.getLong("remaining_budget_ms"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                fromJson(row.getString("payload")));
    }

    private ToolCallJournalEntry mapJournal(ResultSet row, int rowNumber) throws SQLException {
        return codec.decodeJournal(
                row.getInt("schema_version"),
                row.getString("session_id"),
                row.getString("request_id"),
                row.getString("turn_id"),
                row.getObject("attempt_id", UUID.class).toString(),
                row.getInt("step"),
                row.getInt("call_index"),
                row.getString("tool_call_id"),
                row.getString("tool_name"),
                row.getString("tool_schema_version"),
                row.getString("effect"),
                row.getString("status"),
                row.getString("arguments_digest"),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                fromJson(row.getString("payload")));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("checkpoint contains a non-serializable value", exception);
        }
    }

    private Map<String, Object> fromJson(String value) {
        try {
            return objectMapper.readValue(value, MAP_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize Agent recovery state", exception);
        }
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
