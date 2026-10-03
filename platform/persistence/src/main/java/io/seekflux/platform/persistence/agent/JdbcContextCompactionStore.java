package io.seekflux.platform.persistence.agent;

import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextCompactionStore;
import io.seekflux.platform.agentruntime.domain.model.context.CompactionSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcContextCompactionStore implements ContextCompactionStore {

    private final JdbcClient jdbcClient;

    public JdbcContextCompactionStore(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public Optional<CompactionSummary> latest(String sessionId) {
        return jdbcClient.sql("""
                        SELECT summary_id, session_id, schema_version, strategy_version,
                               from_exclusive, inclusive_cutoff, summary, estimated_tokens, created_at
                        FROM agent.context_compactions
                        WHERE session_id = :sessionId
                        ORDER BY inclusive_cutoff DESC, created_at DESC
                        LIMIT 1
                        """)
                .param("sessionId", sessionId)
                .query(this::mapSummary)
                .optional();
    }

    @Override
    @Transactional
    public CompactionSummary append(CompactionSummary summary) {
        boolean locked = jdbcClient.sql("""
                        SELECT session_id
                        FROM agent.sessions
                        WHERE session_id = :sessionId
                        FOR UPDATE
                        """)
                .param("sessionId", summary.sessionId())
                .query(String.class)
                .optional()
                .isPresent();
        if (!locked) {
            throw new IllegalStateException("context compaction session does not exist");
        }
        Optional<CompactionSummary> current = latest(summary.sessionId());
        if (current.isPresent()) {
            CompactionSummary existing = current.get();
            if (existing.inclusiveCutoff() >= summary.inclusiveCutoff()) {
                return existing;
            }
            if (existing.inclusiveCutoff() != summary.fromExclusive()) {
                throw new IllegalStateException("context compaction would create a summary gap");
            }
        } else if (summary.fromExclusive() != 0) {
            throw new IllegalStateException("first context compaction must start at cutoff zero");
        }
        int inserted = jdbcClient.sql("""
                        INSERT INTO agent.context_compactions (
                            summary_id, session_id, schema_version, strategy_version,
                            from_exclusive, inclusive_cutoff, summary, estimated_tokens, created_at
                        ) VALUES (
                            :summaryId, :sessionId, :schemaVersion, :strategyVersion,
                            :fromExclusive, :inclusiveCutoff, :summary, :estimatedTokens, :createdAt
                        )
                        ON CONFLICT (session_id, inclusive_cutoff, strategy_version) DO NOTHING
                        """)
                .param("summaryId", UUID.fromString(summary.summaryId()))
                .param("sessionId", summary.sessionId())
                .param("schemaVersion", summary.schemaVersion())
                .param("strategyVersion", summary.strategyVersion())
                .param("fromExclusive", summary.fromExclusive())
                .param("inclusiveCutoff", summary.inclusiveCutoff())
                .param("summary", summary.summary())
                .param("estimatedTokens", summary.estimatedTokens())
                .param("createdAt", summary.createdAt().atOffset(ZoneOffset.UTC))
                .update();
        if (inserted == 1) {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            new JdbcWorkspaceFacts(jdbcClient, mapper).fact(summary.sessionId(),
                    "compaction:" + summary.summaryId(), "COMPACTION_COMMITTED", null, null,
                    java.util.Map.of("summaryId", summary.summaryId(), "fromExclusive", summary.fromExclusive(),
                            "inclusiveCutoff", summary.inclusiveCutoff(), "strategyVersion", summary.strategyVersion()),
                    summary.createdAt());
            return summary;
        }
        return latest(summary.sessionId())
                .orElseThrow(() -> new IllegalStateException("context compaction conflict has no winner"));
    }

    private CompactionSummary mapSummary(ResultSet row, int rowNumber) throws SQLException {
        return new CompactionSummary(
                row.getInt("schema_version"),
                row.getObject("summary_id", UUID.class).toString(),
                row.getString("session_id"),
                row.getLong("from_exclusive"),
                row.getLong("inclusive_cutoff"),
                row.getString("strategy_version"),
                row.getString("summary"),
                row.getInt("estimated_tokens"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
