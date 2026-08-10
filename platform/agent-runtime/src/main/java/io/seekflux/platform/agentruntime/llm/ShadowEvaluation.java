package io.seekflux.platform.agentruntime.llm;

import java.time.Instant;
import java.util.UUID;

public record ShadowEvaluation(
        UUID evaluationId,
        String requestId,
        String sessionId,
        int step,
        String primaryVersion,
        String shadowVersion,
        String primaryDecision,
        String shadowDecision,
        boolean agreed,
        long shadowTookMillis,
        String errorCode,
        Instant createdAt) {
}
