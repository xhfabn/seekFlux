package io.seekflux.platform.persistence.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.domain.exception.AgentExecutionFencedException;
import io.seekflux.platform.agentruntime.domain.model.recovery.CheckpointBoundary;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeAction;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeIngress;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolJournalStatus;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectStatus;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolutionResult;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import java.nio.charset.StandardCharsets;
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
    private final JdbcWorkspaceFacts facts;
    private final JdbcAgentSessionStore sessions;

    public JdbcAgentRecoveryStore(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this(jdbcClient, objectMapper, new JdbcAgentSessionStore(jdbcClient, objectMapper));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcAgentRecoveryStore(JdbcClient jdbcClient, ObjectMapper objectMapper, JdbcAgentSessionStore sessions) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.codec = new AgentRecoveryCodec(objectMapper);
        this.facts = new JdbcWorkspaceFacts(jdbcClient, objectMapper);
        this.sessions = sessions;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public boolean sideEffectLedgerEnabled() {
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
        jdbcClient.sql("""
                        UPDATE agent.tool_side_effect_ledger
                        SET status = 'UNKNOWN',
                            fencing_token = :fencingToken,
                            payload = jsonb_set(payload, '{status}', '"UNKNOWN"'::jsonb),
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

        if (restored.boundary() == CheckpointBoundary.SUSPENDED
                && restored.terminalResult() != null
                && restored.terminalResult().state()
                        == io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.WAITING) {
            WaitState checkpointWait = restored.terminalResult().waitState();
            if (checkpointWait == null) {
                throw new IllegalStateException("waiting checkpoint has no WaitState");
            }
            Optional<String> waitStatus = jdbcClient.sql("""
                            SELECT status
                            FROM agent.runtime_waits
                            WHERE wait_id = :waitId
                            """)
                    .param("waitId", UUID.fromString(checkpointWait.waitId()))
                    .query(String.class)
                    .optional();
            if (waitStatus.isEmpty()) {
                if (checkpointWait.missingPendingPolicy()
                                == WaitState.MissingPendingPolicy.FAIL_FAST) {
                    throw new IllegalStateException("pending WaitState is missing");
                }
                return new RecoveryPlan(ResumeAction.WAIT_FOR_EXTERNAL, restored, calls);
            } else if ("PENDING".equals(waitStatus.get())) {
                return new RecoveryPlan(ResumeAction.WAIT_FOR_EXTERNAL, restored, calls);
            }
        }

        if (restored.terminal()) {
            if (restored.boundary() != CheckpointBoundary.SUSPENDED
                    || restored.terminalResult().state()
                            != io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.WAITING) {
                return new RecoveryPlan(ResumeAction.COMMIT_TERMINAL, restored, calls);
            }
        }
        if (restored.boundary() == CheckpointBoundary.POST_TURN) {
            return new RecoveryPlan(ResumeAction.RESUME_POST_TURN, restored, List.of());
        }
        List<ToolCallJournalEntry> currentStepCalls = calls.stream()
                .filter(call -> call.step() == restored.nextStep())
                .toList();
        boolean unsafeUnknown = currentStepCalls.stream().anyMatch(call ->
                call.status() == ToolJournalStatus.UNKNOWN && !call.safeToRetry());
        if (unsafeUnknown && !sideEffectLedgerEnabled()) {
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
        facts.lock(checkpoint.sessionId(), fencingToken);
        Map<String, Object> references = persistCheckpointFacts(checkpoint, eventTime);
        long cutoff = ((Number) references.get("statePosition")).longValue();
        String payload = toJson(references);
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
                .param("schemaVersion", 2)
                .param("sessionId", checkpoint.sessionId())
                .param("requestId", checkpoint.requestId())
                .param("turnId", checkpoint.turnId())
                .param("attemptId", UUID.fromString(checkpoint.attemptId()))
                .param("boundary", checkpoint.boundary().name())
                .param("fencingToken", fencingToken)
                .param("messageCutoff", cutoff)
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
        sessions.saveSnapshotIfNeeded(checkpoint.sessionId(), false);
    }

    @Override
    @Transactional
    public void recordToolDecision(
            RuntimeCheckpoint checkpoint,
            List<ToolCallJournalEntry> calls,
            long fencingToken,
            Instant eventTime) {
        facts.lock(checkpoint.sessionId(), fencingToken);
        for (ToolCallJournalEntry call : calls) {
            facts.message(checkpoint.sessionId(), call.assistantMessage(), Map.of(), eventTime);
        }
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
                    .param("schemaVersion", 2)
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
                    .param("payload", toJson(codec.encodeJournalReferences(call)))
                    .param("eventTime", databaseTime(eventTime))
                    .update();
            if (rows != 1) {
                assertExistingDecision(call, fencingToken);
            }
        }
    }

    @Override
    @Transactional
    public void suspendWait(
            RuntimeCheckpoint checkpoint,
            WaitState waitState,
            ToolCallJournalEntry waitingCall,
            long fencingToken,
            Instant eventTime) {
        if (checkpoint.boundary() != CheckpointBoundary.SUSPENDED
                || checkpoint.terminalResult() == null
                || checkpoint.terminalResult().state()
                        != io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.WAITING) {
            throw new IllegalArgumentException("a durable wait requires a suspended waiting checkpoint");
        }
        if (waitingCall.status() != ToolJournalStatus.WAITING
                || !waitingCall.toolCallId().equals(waitState.toolCallId())
                || !checkpoint.checkpointId().equals(waitState.checkpointId())) {
            throw new IllegalArgumentException("WaitState does not match its checkpoint and Tool call");
        }
        saveCheckpoint(checkpoint, fencingToken, eventTime);
        int journalRows = jdbcClient.sql("""
                        UPDATE agent.tool_call_journal journal
                        SET status = 'WAITING',
                            fencing_token = :fencingToken,
                            updated_at = :eventTime
                        WHERE journal.tool_call_id = :toolCallId
                          AND journal.session_id = :sessionId
                          AND journal.request_id = :requestId
                          AND journal.status IN ('DECIDED', 'EXECUTING', 'WAITING')
                          AND EXISTS (
                              SELECT 1 FROM agent.sessions session
                              WHERE session.session_id = journal.session_id
                                AND session.active_fencing_token = :fencingToken
                                AND session.status = 'EXECUTING'
                          )
                        """)
                .param("fencingToken", fencingToken)
                .param("eventTime", databaseTime(eventTime))
                .param("toolCallId", waitingCall.toolCallId())
                .param("sessionId", waitState.sessionId())
                .param("requestId", waitState.requestId())
                .update();
        if (journalRows != 1) {
            throw new AgentExecutionFencedException(waitState.sessionId(), fencingToken);
        }
        int waitRows = jdbcClient.sql("""
                        INSERT INTO agent.runtime_waits (
                            wait_id, schema_version, session_id, request_id, turn_id,
                            checkpoint_id, tool_call_id, wait_type, status, resolution_id,
                            deadline_at, fencing_token, payload, resolution, created_at, updated_at
                        ) VALUES (
                            :waitId, :schemaVersion, :sessionId, :requestId, :turnId,
                            :checkpointId, :toolCallId, :waitType, 'PENDING', NULL,
                            :deadlineAt, :fencingToken, CAST(:payload AS jsonb), NULL,
                            :createdAt, :eventTime
                        )
                        ON CONFLICT (wait_id) DO NOTHING
                        """)
                .param("waitId", UUID.fromString(waitState.waitId()))
                .param("schemaVersion", waitState.schemaVersion())
                .param("sessionId", waitState.sessionId())
                .param("requestId", waitState.requestId())
                .param("turnId", waitState.turnId())
                .param("checkpointId", UUID.fromString(waitState.checkpointId()))
                .param("toolCallId", waitState.toolCallId())
                .param("waitType", waitState.type().name())
                .param("deadlineAt", databaseTime(waitState.deadlineAt()))
                .param("fencingToken", fencingToken)
                .param("payload", toJson(codec.encodeWaitState(waitState)))
                .param("createdAt", databaseTime(waitState.createdAt()))
                .param("eventTime", databaseTime(eventTime))
                .update();
        if (waitRows != 1 && findStoredWait(waitState.waitId()).isEmpty()) {
            throw new AgentExecutionFencedException(waitState.sessionId(), fencingToken);
        }
    }

    @Override
    @Transactional
    public WaitResolutionResult resolveWait(
            WaitResolution resolution, long fencingToken, Instant eventTime) {
        StoredWait stored = findStoredWaitForUpdate(resolution.waitId()).orElse(null);
        if (stored == null) {
            reconstructTolerantWait(resolution, fencingToken, eventTime);
            stored = findStoredWaitForUpdate(resolution.waitId()).orElse(null);
        }
        if (stored == null) {
            return WaitResolutionResult.missing();
        }
        WaitState waitState = stored.waitState();
        if (!waitState.sessionId().equals(resolution.sessionId())
                || !waitState.requestId().equals(resolution.requestId())
                || !waitState.turnId().equals(resolution.turnId())) {
            return WaitResolutionResult.conflict(waitState, stored.resolution());
        }
        if (!"PENDING".equals(stored.status())) {
            return stored.resolution() != null
                            && stored.resolution().sameDecisionAs(resolution)
                    ? WaitResolutionResult.duplicate(waitState, stored.resolution())
                    : WaitResolutionResult.conflict(waitState, stored.resolution());
        }
        if (!waitState.accepts(resolution.outcome())) {
            return WaitResolutionResult.conflict(waitState, null);
        }
        if (resolution.outcome() != WaitResolution.Outcome.TIMED_OUT
                && eventTime.isAfter(waitState.deadlineAt())) {
            return WaitResolutionResult.conflict(waitState, null);
        }
        assertAuthorityForWait(waitState.sessionId(), fencingToken);
        int rows = jdbcClient.sql("""
                        UPDATE agent.runtime_waits
                        SET status = :status,
                            resolution_id = :resolutionId,
                            resolution = CAST(:resolution AS jsonb),
                            fencing_token = :fencingToken,
                            updated_at = :eventTime
                        WHERE wait_id = :waitId AND status = 'PENDING'
                        """)
                .param("status", resolution.outcome().name())
                .param("resolutionId", resolution.resolutionId())
                .param("resolution", toJson(codec.encodeWaitResolution(resolution)))
                .param("fencingToken", fencingToken)
                .param("eventTime", databaseTime(eventTime))
                .param("waitId", UUID.fromString(waitState.waitId()))
                .update();
        if (rows != 1) {
            StoredWait raced = findStoredWaitForUpdate(resolution.waitId()).orElseThrow();
            return raced.resolution() != null && raced.resolution().sameDecisionAs(resolution)
                    ? WaitResolutionResult.duplicate(raced.waitState(), raced.resolution())
                    : WaitResolutionResult.conflict(raced.waitState(), raced.resolution());
        }
        applyWaitResolution(waitState, resolution, fencingToken, eventTime);
        appendWaitResolvedEvent(waitState, resolution, fencingToken, eventTime);
        return WaitResolutionResult.committed(waitState, resolution);
    }

    @Override
    public List<WaitState> findExpiredWaits(Instant deadlineExclusive, int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("expired wait query limit must be between 1 and 1000");
        }
        return jdbcClient.sql("""
                        SELECT wait_type, status, payload::text, resolution::text
                        FROM agent.runtime_waits
                        WHERE status = 'PENDING' AND deadline_at < :deadline
                        ORDER BY deadline_at, wait_id
                        LIMIT :limit
                        """)
                .param("deadline", databaseTime(deadlineExclusive))
                .param("limit", limit)
                .query((row, rowNumber) -> codec.decodeWaitState(fromJson(row.getString("payload"))))
                .list();
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
            facts.fact(sessionId, "tool-dispatch:" + toolCallId + ":" + attemptId,
                    "TOOL_DISPATCHED", requestId, null,
                    Map.of("toolCallId", toolCallId, "attemptId", attemptId, "fencingToken", fencingToken), eventTime);
        }
    }

    @Override
    @Transactional
    public void recordToolResult(
            ToolCallJournalEntry call, long fencingToken, Instant eventTime) {
        recordToolResult(call, resultMessage(call), fencingToken, eventTime);
    }

    @Override
    @Transactional
    public void recordToolResult(ToolCallJournalEntry call, AgentMessage.ToolResult message,
                                 long fencingToken, Instant eventTime) {
        if (!call.status().terminal()) {
            throw new IllegalArgumentException("Tool result journal state must be terminal");
        }
        facts.lock(call.sessionId(), fencingToken);
        if (!message.toolCallId().equals(call.toolCallId())) {
            throw new IllegalArgumentException("Tool message and journal identity differ");
        }
        facts.message(call.sessionId(), message,
                Map.of("observation", codec.encodeObservationMetadata(call.observation())), eventTime);
        String payload = toJson(codec.encodeJournalReferences(call));
        int rows = jdbcClient.sql("""
                        UPDATE agent.tool_call_journal journal
                        SET status = :status,
                            schema_version = 2,
                            attempt_id = :attemptId,
                            fencing_token = :fencingToken,
                            payload = CAST(:payload AS jsonb),
                            updated_at = :eventTime
                        WHERE journal.tool_call_id = :toolCallId
                          AND journal.session_id = :sessionId
                          AND journal.request_id = :requestId
                          AND journal.status IN ('DECIDED', 'EXECUTING', 'UNKNOWN', 'WAITING')
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

    @Override
    @Transactional
    public SideEffectLedgerEntry prepareSideEffect(
            SideEffectLedgerEntry entry, long fencingToken, Instant eventTime) {
        if (entry.status() != SideEffectStatus.PREPARED) {
            throw new IllegalArgumentException("new side-effect ledger entry must be PREPARED");
        }
        int rows = jdbcClient.sql("""
                        INSERT INTO agent.tool_side_effect_ledger (
                            ledger_id, schema_version, session_id, request_id, turn_id,
                            attempt_id, step, call_index, tool_call_id, tool_name,
                            tool_schema_version, idempotency_key, status, request_digest,
                            result_digest, fencing_token, payload, created_at, updated_at
                        )
                        SELECT :ledgerId, :schemaVersion, :sessionId, :requestId, :turnId,
                               :attemptId, :step, :callIndex, :toolCallId, :toolName,
                               :toolSchemaVersion, :idempotencyKey, 'PREPARED', :requestDigest,
                               NULL, :fencingToken, CAST(:payload AS jsonb), :createdAt, :eventTime
                        FROM agent.sessions
                        WHERE session_id = :sessionId
                          AND active_fencing_token = :fencingToken
                        ON CONFLICT (tool_call_id) DO NOTHING
                        """)
                .param("ledgerId", UUID.fromString(entry.ledgerId()))
                .param("schemaVersion", entry.schemaVersion())
                .param("sessionId", entry.sessionId())
                .param("requestId", entry.requestId())
                .param("turnId", entry.turnId())
                .param("attemptId", UUID.fromString(entry.attemptId()))
                .param("step", entry.step())
                .param("callIndex", entry.callIndex())
                .param("toolCallId", entry.toolCallId())
                .param("toolName", entry.toolName())
                .param("toolSchemaVersion", entry.toolSchemaVersion())
                .param("idempotencyKey", entry.idempotencyKey())
                .param("requestDigest", entry.requestDigest())
                .param("fencingToken", fencingToken)
                .param("payload", sideEffectJson(entry))
                .param("createdAt", databaseTime(entry.createdAt()))
                .param("eventTime", databaseTime(eventTime))
                .update();
        SideEffectLedgerEntry persisted = findSideEffect(entry.toolCallId())
                .orElseThrow(() -> new AgentExecutionFencedException(entry.sessionId(), fencingToken));
        if (rows == 0 && (!persisted.requestDigest().equals(entry.requestDigest())
                || !persisted.idempotencyKey().equals(entry.idempotencyKey()))) {
            throw new IllegalStateException("side-effect ledger identity conflict");
        }
        assertAuthority(entry.sessionId(), fencingToken);
        return persisted;
    }

    @Override
    @Transactional
    public SideEffectLedgerEntry markSideEffectExecuting(
            SideEffectLedgerEntry entry, long fencingToken, Instant eventTime) {
        if (entry.status() != SideEffectStatus.EXECUTING) {
            throw new IllegalArgumentException("side-effect ledger dispatch must be EXECUTING");
        }
        int rows = jdbcClient.sql("""
                        UPDATE agent.tool_side_effect_ledger ledger
                        SET status = 'EXECUTING',
                            attempt_id = :attemptId,
                            fencing_token = :fencingToken,
                            payload = CAST(:payload AS jsonb),
                            updated_at = :eventTime
                        WHERE ledger.tool_call_id = :toolCallId
                          AND ledger.status = 'PREPARED'
                          AND EXISTS (
                              SELECT 1 FROM agent.sessions session
                              WHERE session.session_id = ledger.session_id
                                AND session.active_fencing_token = :fencingToken
                          )
                        """)
                .param("attemptId", UUID.fromString(entry.attemptId()))
                .param("fencingToken", fencingToken)
                .param("payload", sideEffectJson(entry))
                .param("eventTime", databaseTime(eventTime))
                .param("toolCallId", entry.toolCallId())
                .update();
        SideEffectLedgerEntry persisted = findSideEffect(entry.toolCallId())
                .orElseThrow(() -> new AgentExecutionFencedException(entry.sessionId(), fencingToken));
        if (rows != 1 && !(persisted.status() == SideEffectStatus.EXECUTING
                && persisted.attemptId().equals(entry.attemptId()))) {
            throw new IllegalStateException("mutating Tool side effect is not dispatchable");
        }
        assertAuthority(entry.sessionId(), fencingToken);
        return persisted;
    }

    @Override
    @Transactional
    public SideEffectLedgerEntry recordSideEffectResult(
            SideEffectLedgerEntry entry, long fencingToken, Instant eventTime) {
        if (!entry.status().terminal()) {
            throw new IllegalArgumentException("side-effect result must be terminal");
        }
        int rows = jdbcClient.sql("""
                        UPDATE agent.tool_side_effect_ledger ledger
                        SET status = :status,
                            attempt_id = :attemptId,
                            result_digest = :resultDigest,
                            fencing_token = :fencingToken,
                            payload = CAST(:payload AS jsonb),
                            updated_at = :eventTime
                        WHERE ledger.tool_call_id = :toolCallId
                          AND ledger.status IN ('EXECUTING', 'UNKNOWN')
                          AND EXISTS (
                              SELECT 1 FROM agent.sessions session
                              WHERE session.session_id = ledger.session_id
                                AND session.active_fencing_token = :fencingToken
                          )
                        """)
                .param("status", entry.status().name())
                .param("attemptId", UUID.fromString(entry.attemptId()))
                .param("resultDigest", entry.resultDigest())
                .param("fencingToken", fencingToken)
                .param("payload", sideEffectJson(entry))
                .param("eventTime", databaseTime(eventTime))
                .param("toolCallId", entry.toolCallId())
                .update();
        SideEffectLedgerEntry persisted = findSideEffect(entry.toolCallId())
                .orElseThrow(() -> new AgentExecutionFencedException(entry.sessionId(), fencingToken));
        if (rows != 1 && !(persisted.status() == entry.status()
                && persisted.resultDigest().equals(entry.resultDigest()))) {
            throw new AgentExecutionFencedException(entry.sessionId(), fencingToken);
        }
        assertAuthority(entry.sessionId(), fencingToken);
        return persisted;
    }

    @Override
    @Transactional
    public void markSideEffectsUnknown(
            String sessionId,
            String requestId,
            List<String> toolCallIds,
            long fencingToken,
            Instant eventTime) {
        assertAuthority(sessionId, fencingToken);
        for (String toolCallId : toolCallIds) {
            jdbcClient.sql("""
                            UPDATE agent.tool_side_effect_ledger
                            SET status = 'UNKNOWN',
                                fencing_token = :fencingToken,
                                payload = jsonb_set(payload, '{status}', '"UNKNOWN"'::jsonb),
                                updated_at = :eventTime
                            WHERE tool_call_id = :toolCallId
                              AND session_id = :sessionId
                              AND request_id = :requestId
                              AND status = 'EXECUTING'
                            """)
                    .param("toolCallId", toolCallId)
                    .param("sessionId", sessionId)
                    .param("requestId", requestId)
                    .param("fencingToken", fencingToken)
                    .param("eventTime", databaseTime(eventTime))
                    .update();
        }
    }

    @Override
    public Optional<SideEffectLedgerEntry> findSideEffect(String toolCallId) {
        return jdbcClient.sql("""
                        SELECT status, updated_at, payload::text
                        FROM agent.tool_side_effect_ledger
                        WHERE tool_call_id = :toolCallId
                        """)
                .param("toolCallId", toolCallId)
                .query((row, rowNumber) -> sideEffectFromJson(row.getString("payload"))
                        .withStoredState(
                                SideEffectStatus.valueOf(row.getString("status")),
                                row.getObject("updated_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private void applyWaitResolution(
            WaitState waitState,
            WaitResolution resolution,
            long fencingToken,
            Instant eventTime) {
        if (resolution.resumesToolExecution()) {
            int rows = jdbcClient.sql("""
                            UPDATE agent.tool_call_journal
                            SET status = 'DECIDED',
                                fencing_token = :fencingToken,
                                updated_at = :eventTime
                            WHERE tool_call_id = :toolCallId
                              AND session_id = :sessionId
                              AND request_id = :requestId
                              AND status = 'WAITING'
                            """)
                    .param("fencingToken", fencingToken)
                    .param("eventTime", databaseTime(eventTime))
                    .param("toolCallId", waitState.toolCallId())
                    .param("sessionId", waitState.sessionId())
                    .param("requestId", waitState.requestId())
                    .update();
            if (rows != 1) {
                throw new IllegalStateException("approved wait has no waiting Tool call");
            }
            return;
        }
        ToolCallJournalEntry call = jdbcClient.sql("""
                        SELECT schema_version, session_id, request_id, turn_id, attempt_id,
                               step, call_index, tool_call_id, tool_name, tool_schema_version,
                               effect, status, arguments_digest, updated_at, payload::text
                        FROM agent.tool_call_journal
                        WHERE tool_call_id = :toolCallId
                        """)
                .param("toolCallId", waitState.toolCallId())
                .query(this::mapJournal)
                .optional()
                .orElseThrow(() -> new IllegalStateException("wait has no Tool journal entry"));
        ToolJournalStatus status = switch (resolution.outcome()) {
            case COMPLETED -> ToolJournalStatus.SUCCEEDED;
            case CANCELLED -> ToolJournalStatus.CANCELLED;
            case TIMED_OUT -> ToolJournalStatus.TIMED_OUT;
            case DENIED, FAILED -> ToolJournalStatus.FAILED;
            case APPROVED -> throw new IllegalStateException("approval should resume Tool execution");
        };
        String errorCode = switch (resolution.outcome()) {
            case COMPLETED -> null;
            case DENIED -> "TOOL_APPROVAL_DENIED";
            case TIMED_OUT -> resolution.errorCode();
            case CANCELLED -> resolution.errorCode() == null
                    ? "USER_CANCEL" : resolution.errorCode();
            case FAILED -> resolution.errorCode();
            case APPROVED -> null;
        };
        AgentToolResult result = status == ToolJournalStatus.SUCCEEDED
                ? AgentToolResult.success(resolution.output(), null)
                : AgentToolResult.failure(errorCode);
        AgentToolObservation observation = new AgentToolObservation(
                call.toolCallId(),
                call.toolName(),
                call.toolSchemaVersion(),
                call.arguments(),
                call.argumentsRepaired(),
                result,
                0);
        recordToolResult(
                call.forAttempt(call.attemptId(), status, observation, eventTime),
                fencingToken,
                eventTime);
    }

    private void appendWaitResolvedEvent(
            WaitState waitState,
            WaitResolution resolution,
            long fencingToken,
            Instant eventTime) {
        long position = jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET event_position = event_position + 1,
                            version = version + 1,
                            status = 'EXECUTING',
                            updated_at = :eventTime
                        WHERE session_id = :sessionId
                          AND active_fencing_token = :fencingToken
                        RETURNING event_position
                        """)
                .param("eventTime", databaseTime(eventTime))
                .param("sessionId", waitState.sessionId())
                .param("fencingToken", fencingToken)
                .query(Long.class)
                .optional()
                .orElseThrow(() -> new AgentExecutionFencedException(
                        waitState.sessionId(), fencingToken));
        UUID eventId;
        try {
            eventId = UUID.fromString(resolution.resolutionId());
        } catch (IllegalArgumentException invalidUuid) {
            eventId = UUID.nameUUIDFromBytes(
                    ("wait-resolution:" + resolution.resolutionId())
                            .getBytes(StandardCharsets.UTF_8));
        }
        int inserted = jdbcClient.sql("""
                        INSERT INTO agent.workspace_events (
                            event_id, session_id, event_position, event_type, schema_version,
                            message_id, tool_call_id, request_id, turn_id, event_time, payload
                        ) VALUES (
                            :eventId, :sessionId, :position, 'WAIT_RESOLVED', :schemaVersion,
                            NULL, :toolCallId, :requestId, :turnId, :eventTime,
                            CAST(:payload AS jsonb)
                        )
                        """)
                .param("eventId", eventId)
                .param("sessionId", waitState.sessionId())
                .param("position", position)
                .param("schemaVersion", resolution.schemaVersion())
                .param("toolCallId", waitState.toolCallId())
                .param("requestId", waitState.requestId())
                .param("turnId", waitState.turnId())
                .param("eventTime", databaseTime(eventTime))
                .param("payload", toJson(codec.encodeWaitResolution(resolution)))
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("wait resolution event was not appended");
        }
    }

    private Optional<StoredWait> findStoredWait(String waitId) {
        return findStoredWait(waitId, false);
    }

    private Optional<StoredWait> findStoredWaitForUpdate(String waitId) {
        return findStoredWait(waitId, true);
    }

    private Optional<StoredWait> findStoredWait(String waitId, boolean lock) {
        String sql = """
                SELECT status, payload::text, resolution::text
                FROM agent.runtime_waits
                WHERE wait_id = :waitId
                """ + (lock ? " FOR UPDATE" : "");
        return jdbcClient.sql(sql)
                .param("waitId", UUID.fromString(waitId))
                .query((row, rowNumber) -> {
                    WaitState state = codec.decodeWaitState(fromJson(row.getString("payload")));
                    String resolutionJson = row.getString("resolution");
                    WaitResolution resolution = resolutionJson == null
                            ? null : codec.decodeWaitResolution(fromJson(resolutionJson));
                    return new StoredWait(row.getString("status"), state, resolution);
                })
                .optional();
    }

    private void reconstructTolerantWait(
            WaitResolution resolution, long fencingToken, Instant eventTime) {
        Optional<RuntimeCheckpoint> checkpoint = jdbcClient.sql("""
                        SELECT checkpoint_id, schema_version, session_id, request_id, turn_id,
                               attempt_id, boundary, fencing_token, message_cutoff, next_step,
                               tool_call_count, remaining_budget_ms, created_at, payload::text
                        FROM agent.runtime_checkpoints
                        WHERE session_id = :sessionId AND request_id = :requestId
                        """)
                .param("sessionId", resolution.sessionId())
                .param("requestId", resolution.requestId())
                .query(this::mapCheckpoint)
                .optional();
        if (checkpoint.isEmpty() || checkpoint.get().terminalResult() == null) {
            return;
        }
        WaitState state = checkpoint.get().terminalResult().waitState();
        if (state == null
                || !state.waitId().equals(resolution.waitId())
                || state.missingPendingPolicy() != WaitState.MissingPendingPolicy.TOLERATE_CALLBACK_RESULT) {
            return;
        }
        assertAuthorityForWait(state.sessionId(), fencingToken);
        jdbcClient.sql("""
                        INSERT INTO agent.runtime_waits (
                            wait_id, schema_version, session_id, request_id, turn_id,
                            checkpoint_id, tool_call_id, wait_type, status, resolution_id,
                            deadline_at, fencing_token, payload, resolution, created_at, updated_at
                        ) VALUES (
                            :waitId, :schemaVersion, :sessionId, :requestId, :turnId,
                            :checkpointId, :toolCallId, :waitType, 'PENDING', NULL,
                            :deadlineAt, :fencingToken, CAST(:payload AS jsonb), NULL,
                            :createdAt, :eventTime
                        )
                        ON CONFLICT (wait_id) DO NOTHING
                        """)
                .param("waitId", UUID.fromString(state.waitId()))
                .param("schemaVersion", state.schemaVersion())
                .param("sessionId", state.sessionId())
                .param("requestId", state.requestId())
                .param("turnId", state.turnId())
                .param("checkpointId", UUID.fromString(state.checkpointId()))
                .param("toolCallId", state.toolCallId())
                .param("waitType", state.type().name())
                .param("deadlineAt", databaseTime(state.deadlineAt()))
                .param("fencingToken", fencingToken)
                .param("payload", toJson(codec.encodeWaitState(state)))
                .param("createdAt", databaseTime(state.createdAt()))
                .param("eventTime", databaseTime(eventTime))
                .update();
        jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET status = 'SUSPENDED'
                        WHERE session_id = :sessionId
                          AND active_fencing_token = :fencingToken
                        """)
                .param("sessionId", state.sessionId())
                .param("fencingToken", fencingToken)
                .update();
    }

    private void assertAuthorityForWait(String sessionId, long fencingToken) {
        int rows = jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET active_fencing_token = :fencingToken,
                            updated_at = updated_at
                        WHERE session_id = :sessionId
                          AND active_fencing_token <= :fencingToken
                          AND status = 'SUSPENDED'
                        """)
                .param("sessionId", sessionId)
                .param("fencingToken", fencingToken)
                .update();
        if (rows != 1) {
            throw new AgentExecutionFencedException(sessionId, fencingToken);
        }
        jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET status = 'EXECUTING'
                        WHERE session_id = :sessionId
                          AND active_fencing_token = :fencingToken
                        """)
                .param("sessionId", sessionId)
                .param("fencingToken", fencingToken)
                .update();
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
        facts.lock(sessionId, fencingToken);
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
        Map<String, Object> payload = fromJson(row.getString("payload"));
        if (payload.containsKey("statePosition")) {
            payload = hydrateCheckpoint(row, payload);
        }
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
                payload);
    }

    private ToolCallJournalEntry mapJournal(ResultSet row, int rowNumber) throws SQLException {
        Map<String, Object> payload = new java.util.LinkedHashMap<>(fromJson(row.getString("payload")));
        if (payload.containsKey("assistantMessageId")) {
            payload.put("assistantMessage", loadMessage(row.getString("session_id"),
                    String.valueOf(payload.get("assistantMessageId"))));
            if (payload.containsKey("observationToolCallId")) {
                payload.put("observation", loadObservation(row.getString("session_id"),
                        String.valueOf(payload.get("observationToolCallId"))));
            }
        }
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
                payload);
    }

    private Map<String, Object> persistCheckpointFacts(RuntimeCheckpoint checkpoint, Instant time) {
        String prefix = checkpoint.sessionId() + ":" + checkpoint.requestId();
        // Old v1 checkpoints are hydrated in memory and migrate by appending missing facts once.
        java.util.Set<String> existingMessageIds = new java.util.HashSet<>(jdbcClient.sql("""
                SELECT message_id FROM agent.workspace_events
                WHERE session_id = :sessionId AND request_id = :requestId AND message_id IS NOT NULL
                """).param("sessionId", checkpoint.sessionId()).param("requestId", checkpoint.requestId())
                .query(String.class).list());
        for (AgentMessage message : checkpoint.messages()) {
            if (existingMessageIds.contains(message.messageId())) continue;
            Map<String, Object> extra = checkpoint.observations().stream()
                    .filter(observation -> message instanceof AgentMessage.ToolResult result
                            && observation.toolCallId().equals(result.toolCallId()))
                    .findFirst().map(observation -> Map.of("observation", (Object) codec.encodeObservationMetadata(observation)))
                    .orElse(Map.of());
            facts.message(checkpoint.sessionId(), message, extra, time);
        }
        int existingSteps = jdbcClient.sql("""
                SELECT COUNT(*) FROM agent.workspace_events
                WHERE session_id = :sessionId AND request_id = :requestId AND event_type = 'EXECUTION_STEP'
                """).param("sessionId", checkpoint.sessionId()).param("requestId", checkpoint.requestId())
                .query(Integer.class).single();
        for (int index = existingSteps; index < checkpoint.steps().size(); index++) {
            facts.fact(checkpoint.sessionId(), prefix + ":trace:" + index,
                    "EXECUTION_STEP", checkpoint.requestId(), checkpoint.turnId(),
                    Map.of("index", index, "step", objectMapper.convertValue(checkpoint.steps().get(index), MAP_TYPE)), time);
        }
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("definition", objectMapper.convertValue(checkpoint.definition(), MAP_TYPE));
        metadata.put("persistentFeatures", checkpoint.persistentFeatures());
        long metadataPosition = facts.fact(checkpoint.sessionId(), prefix + ":metadata:"
                        + UUID.nameUUIDFromBytes(toJson(metadata).getBytes(StandardCharsets.UTF_8)),
                "EXECUTION_METADATA", checkpoint.requestId(), checkpoint.turnId(), metadata, time);
        Map<String, Object> references = new java.util.LinkedHashMap<>();
        references.put("metadataPosition", metadataPosition);
        if (checkpoint.terminalResult() != null) {
            long terminalPosition = facts.fact(checkpoint.sessionId(),
                    prefix + ":terminal:" + checkpoint.checkpointId() + ":" + checkpoint.attemptId(),
                    "EXECUTION_TERMINAL", checkpoint.requestId(), checkpoint.turnId(),
                    codec.encodeTerminal(checkpoint.terminalResult()), time);
            references.put("terminalPosition", terminalPosition);
        }
        long head = jdbcClient.sql("SELECT event_position FROM agent.sessions WHERE session_id = :sessionId")
                .param("sessionId", checkpoint.sessionId()).query(Long.class).single();
        Map<String, Object> state = new java.util.LinkedHashMap<>(codec.encodeState(checkpoint));
        state.putAll(references);
        long position = facts.fact(checkpoint.sessionId(), prefix + ":state:" + checkpoint.checkpointId()
                        + ":" + checkpoint.attemptId() + ":" + head,
                "EXECUTION_PROGRESS", checkpoint.requestId(), checkpoint.turnId(), state, time);
        references.put("statePosition", position);
        return references;
    }

    private Map<String, Object> hydrateCheckpoint(ResultSet row, Map<String, Object> references) throws SQLException {
        String sessionId = row.getString("session_id");
        String requestId = row.getString("request_id");
        long cutoff = row.getLong("message_cutoff");
        long statePosition = ((Number) references.get("statePosition")).longValue();
        if (cutoff != statePosition) throw new IllegalStateException("checkpoint cursor/reference mismatch");
        Map<String, Object> payload = new java.util.LinkedHashMap<>(facts.payload(sessionId, statePosition));
        payload.putAll(facts.payload(sessionId, ((Number) references.get("metadataPosition")).longValue()));
        int stepLimit = row.getString("boundary").equals("PRE_TURN") ? row.getInt("next_step") - 1 : Integer.MAX_VALUE;
        List<Map<String, Object>> messages = jdbcClient.sql("""
                SELECT event_type, schema_version, message_id, tool_call_id, request_id, turn_id, payload::text
                FROM agent.workspace_events WHERE session_id = :sessionId AND request_id = :requestId
                  AND event_position <= :cutoff AND event_type IN ('ASSISTANT_MESSAGE', 'TOOL_RESULT_MESSAGE')
                  AND (payload ->> 'step')::int <= :stepLimit ORDER BY event_position
                """).param("sessionId", sessionId).param("requestId", requestId).param("cutoff", cutoff)
                .param("stepLimit", stepLimit).query(this::encodedMessage).list();
        payload.put("messages", messages);
        payload.put("observations", messages.stream().filter(message ->
                        "TOOL_RESULT_MESSAGE".equals(message.get("eventType")))
                .map(message -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> messagePayload = (Map<String, Object>) message.get("payload");
                    return codec.observationFromMessage(messagePayload);
                })
                .filter(java.util.Objects::nonNull).toList());
        List<Map<String, Object>> steps = jdbcClient.sql("""
                SELECT payload::text FROM agent.workspace_events
                WHERE session_id = :sessionId AND request_id = :requestId AND event_type = 'EXECUTION_STEP'
                  AND event_position <= :cutoff ORDER BY (payload ->> 'index')::int
                """).param("sessionId", sessionId).param("requestId", requestId).param("cutoff", cutoff)
                .query((value, index) -> fromJson(value.getString(1))).list();
        payload.put("steps", steps.stream().map(step -> step.get("step")).toList());
        if (references.containsKey("terminalPosition")) {
            Map<String, Object> terminal = new java.util.LinkedHashMap<>(facts.payload(sessionId,
                    ((Number) references.get("terminalPosition")).longValue()));
            terminal.put("messages", messages);
            @SuppressWarnings("unchecked")
            Map<String, Object> trace = new java.util.LinkedHashMap<>((Map<String, Object>) terminal.get("trace"));
            trace.put("steps", payload.get("steps"));
            trace.put("definition", payload.get("definition"));
            terminal.put("trace", trace);
            payload.put("terminalResult", terminal);
        }
        return payload;
    }

    private Map<String, Object> encodedMessage(ResultSet row, int index) throws SQLException {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("eventType", row.getString("event_type"));
        value.put("schemaVersion", row.getInt("schema_version"));
        value.put("messageId", row.getString("message_id"));
        value.put("toolCallId", row.getString("tool_call_id"));
        value.put("requestId", row.getString("request_id"));
        value.put("turnId", row.getString("turn_id"));
        value.put("payload", fromJson(row.getString("payload")));
        return value;
    }

    private Map<String, Object> loadMessage(String sessionId, String messageId) {
        return jdbcClient.sql("""
                SELECT event_type, schema_version, message_id, tool_call_id, request_id, turn_id, payload::text
                FROM agent.workspace_events WHERE session_id = :sessionId AND message_id = :messageId
                  AND event_type IN ('ASSISTANT_MESSAGE', 'TOOL_RESULT_MESSAGE')
                """).param("sessionId", sessionId).param("messageId", messageId).query(this::encodedMessage).single();
    }

    private Object loadObservation(String sessionId, String callId) {
        return jdbcClient.sql("""
                SELECT payload::text FROM agent.workspace_events WHERE session_id = :sessionId
                  AND tool_call_id = :callId AND event_type = 'TOOL_RESULT_MESSAGE'
                """).param("sessionId", sessionId).param("callId", callId)
                .query((row, index) -> codec.observationFromMessage(fromJson(row.getString(1)))).single();
    }

    private static AgentMessage.ToolResult resultMessage(ToolCallJournalEntry call) {
        var observation = call.observation();
        AgentMessage.ToolResultStatus status = AgentMessage.ToolResultStatus.valueOf(call.status().name());
        String identity = call.sessionId() + ":" + call.requestId() + ":" + call.turnId()
                + ":tool-result:" + call.toolCallId();
        String content = status == AgentMessage.ToolResultStatus.SUCCEEDED
                ? call.toolName() + ":" + new java.util.TreeMap<>(observation.result().output())
                : call.toolName() + ":" + status.name() + ":" + observation.result().errorCode();
        return new AgentMessage.ToolResult(1, UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString(),
                call.requestId(), call.turnId(), call.attemptId(), call.step(), call.toolCallId(), call.toolName(),
                call.toolSchemaVersion(), status, content, observation.result().output(), observation.result().output(),
                observation.result().output(), List.of(), observation.result().errorCode(), observation.result().linkedTraceId(),
                call.argumentsRepaired(), observation.tookMillis());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("checkpoint contains a non-serializable value", exception);
        }
    }

    private String sideEffectJson(SideEffectLedgerEntry value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("side-effect ledger contains a non-serializable value", exception);
        }
    }

    private SideEffectLedgerEntry sideEffectFromJson(String value) {
        try {
            return objectMapper.readValue(value, SideEffectLedgerEntry.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize side-effect ledger", exception);
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

    private record StoredWait(
            String status,
            WaitState waitState,
            WaitResolution resolution) {
    }
}
