package io.seekflux.platform.persistence.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.AgentRunEvent;
import io.seekflux.platform.agentruntime.AgentRunRecorder;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentRunRecorder implements AgentRunRecorder {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcAgentRunRecorder(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void record(AgentRunEvent event) {
        if (event.type() == AgentRunEvent.Type.RUN_STARTED) {
            startRun(event);
        }
        insertEvent(event);
        if (event.type() == AgentRunEvent.Type.RUN_COMPLETED) {
            completeRun(event);
        }
    }

    private void startRun(AgentRunEvent event) {
        int sessionRows = jdbcClient.sql("""
                        INSERT INTO agent.sessions (
                            session_id, version, last_turn_id, last_agent_run_id,
                            snapshot, created_at, updated_at
                        ) VALUES (
                            :sessionId, 1, :turnId, :agentRunId,
                            '{}'::jsonb, :eventTime, :eventTime
                        )
                        ON CONFLICT (session_id) DO UPDATE
                        SET version = agent.sessions.version + 1,
                            last_turn_id = EXCLUDED.last_turn_id,
                            last_agent_run_id = EXCLUDED.last_agent_run_id,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("sessionId", event.sessionId())
                .param("turnId", event.turnId())
                .param("agentRunId", UUID.fromString(event.agentRunId()))
                .param("eventTime", databaseTime(event.eventTime()))
                .update();
        if (sessionRows != 1) {
            throw new IllegalStateException("agent session upsert did not affect exactly one row");
        }

        int runRows = jdbcClient.sql("""
                        INSERT INTO agent.runs (
                            agent_run_id, request_id, session_id, turn_id,
                            agent_definition, state, started_at
                        ) VALUES (
                            :agentRunId, :requestId, :sessionId, :turnId,
                            CAST(:definition AS jsonb), 'RUNNING', :startedAt
                        )
                        """)
                .param("agentRunId", UUID.fromString(event.agentRunId()))
                .param("requestId", event.requestId())
                .param("sessionId", event.sessionId())
                .param("turnId", event.turnId())
                .param("definition", toJson(event.payload().get("definition")))
                .param("startedAt", databaseTime(event.eventTime()))
                .update();
        if (runRows != 1) {
            throw new IllegalStateException("agent run insert did not affect exactly one row");
        }
    }

    private void insertEvent(AgentRunEvent event) {
        int rows = jdbcClient.sql("""
                        INSERT INTO agent.run_events (
                            event_id, agent_run_id, event_sequence, event_type, event_time, payload
                        ) VALUES (
                            :eventId, :agentRunId, :eventSequence, :eventType, :eventTime,
                            CAST(:payload AS jsonb)
                        )
                        """)
                .param("eventId", event.eventId())
                .param("agentRunId", UUID.fromString(event.agentRunId()))
                .param("eventSequence", event.sequence())
                .param("eventType", event.type().name())
                .param("eventTime", databaseTime(event.eventTime()))
                .param("payload", toJson(event.payload()))
                .update();
        if (rows != 1) {
            throw new IllegalStateException("agent event insert did not affect exactly one row");
        }
    }

    private void completeRun(AgentRunEvent event) {
        String state = String.valueOf(event.payload().get("state"));
        String executionMode = String.valueOf(event.payload().get("executionMode"));
        Object fallbackReason = event.payload().get("fallbackReason");
        Object trace = event.payload().get("trace");
        int runRows = jdbcClient.sql("""
                        UPDATE agent.runs
                        SET state = :state,
                            execution_mode = :executionMode,
                            fallback_reason = :fallbackReason,
                            trace = CAST(:trace AS jsonb),
                            completed_at = :completedAt
                        WHERE agent_run_id = :agentRunId
                          AND state = 'RUNNING'
                        """)
                .param("state", state)
                .param("executionMode", executionMode)
                .param("fallbackReason", fallbackReason, Types.VARCHAR)
                .param("trace", toJson(trace))
                .param("completedAt", databaseTime(event.eventTime()))
                .param("agentRunId", UUID.fromString(event.agentRunId()))
                .update();
        if (runRows != 1) {
            throw new IllegalStateException("agent run completion did not affect exactly one running row");
        }

        Map<String, Object> snapshot = Map.of(
                "lastAgentRunId", event.agentRunId(),
                "lastTurnId", event.turnId(),
                "state", state,
                "executionMode", executionMode);
        int sessionRows = jdbcClient.sql("""
                        UPDATE agent.sessions
                        SET snapshot = CAST(:snapshot AS jsonb),
                            updated_at = :updatedAt
                        WHERE session_id = :sessionId
                          AND last_agent_run_id = :agentRunId
                        """)
                .param("snapshot", toJson(snapshot))
                .param("updatedAt", databaseTime(event.eventTime()))
                .param("sessionId", event.sessionId())
                .param("agentRunId", UUID.fromString(event.agentRunId()))
                .update();
        if (sessionRows != 1) {
            throw new IllegalStateException("agent session snapshot did not affect the current run");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize agent persistence value", exception);
        }
    }

    private static OffsetDateTime databaseTime(java.time.Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
