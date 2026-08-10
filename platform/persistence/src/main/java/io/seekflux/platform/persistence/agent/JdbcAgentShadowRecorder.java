package io.seekflux.platform.persistence.agent;

import io.seekflux.platform.agentruntime.llm.AgentShadowRecorder;
import io.seekflux.platform.agentruntime.llm.ShadowEvaluation;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentShadowRecorder implements AgentShadowRecorder {

    private final JdbcClient jdbcClient;

    public JdbcAgentShadowRecorder(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void record(ShadowEvaluation value) {
        jdbcClient.sql("""
                        INSERT INTO agent.shadow_evaluations (
                            evaluation_id, request_id, session_id, step,
                            primary_version, shadow_version, primary_decision,
                            shadow_decision, agreed, shadow_took_ms, error_code, created_at
                        ) VALUES (
                            :evaluationId, :requestId, :sessionId, :step,
                            :primaryVersion, :shadowVersion, :primaryDecision,
                            :shadowDecision, :agreed, :shadowTookMs, :errorCode, :createdAt
                        )
                        """)
                .param("evaluationId", value.evaluationId())
                .param("requestId", value.requestId())
                .param("sessionId", value.sessionId())
                .param("step", value.step())
                .param("primaryVersion", value.primaryVersion())
                .param("shadowVersion", value.shadowVersion())
                .param("primaryDecision", value.primaryDecision())
                .param("shadowDecision", value.shadowDecision(), Types.VARCHAR)
                .param("agreed", value.agreed())
                .param("shadowTookMs", value.shadowTookMillis())
                .param("errorCode", value.errorCode(), Types.VARCHAR)
                .param("createdAt", OffsetDateTime.ofInstant(value.createdAt(), ZoneOffset.UTC))
                .update();
    }
}
