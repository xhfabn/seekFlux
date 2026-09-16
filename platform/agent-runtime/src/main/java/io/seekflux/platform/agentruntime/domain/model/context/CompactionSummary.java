package io.seekflux.platform.agentruntime.domain.model.context;

import java.time.Instant;

public record CompactionSummary(
        int schemaVersion,
        String summaryId,
        String sessionId,
        long fromExclusive,
        long inclusiveCutoff,
        String strategyVersion,
        String summary,
        int estimatedTokens,
        Instant createdAt) {

    public CompactionSummary {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("compaction schema version must be positive");
        }
        if (summaryId == null || summaryId.isBlank()
                || sessionId == null || sessionId.isBlank()
                || strategyVersion == null || strategyVersion.isBlank()
                || summary == null || summary.isBlank()
                || createdAt == null) {
            throw new IllegalArgumentException("compaction summary identity and content are required");
        }
        if (fromExclusive < 0 || inclusiveCutoff <= fromExclusive) {
            throw new IllegalArgumentException("compaction cutoff must advance without a gap");
        }
        if (estimatedTokens < 1) {
            throw new IllegalArgumentException("compaction token estimate must be positive");
        }
    }
}
