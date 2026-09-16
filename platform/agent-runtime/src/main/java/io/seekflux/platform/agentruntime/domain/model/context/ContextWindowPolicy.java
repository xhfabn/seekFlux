package io.seekflux.platform.agentruntime.domain.model.context;

import java.time.Duration;

public record ContextWindowPolicy(
        int maxInputTokens,
        int targetInputTokens,
        int overflowInputTokens,
        int recentTurns,
        ContextCompactionMode compactionMode,
        Duration compactionTimeout,
        int overflowRetryLimit) {

    public static final ContextWindowPolicy DEFAULT = new ContextWindowPolicy(
            8_192, 6_144, 4_096, 2, ContextCompactionMode.SYNC,
            Duration.ofMillis(500), 1);

    public ContextWindowPolicy {
        if (maxInputTokens < 128) {
            throw new IllegalArgumentException("context max input tokens must be at least 128");
        }
        if (targetInputTokens < 64 || targetInputTokens > maxInputTokens) {
            throw new IllegalArgumentException("context target tokens must be within the maximum");
        }
        if (overflowInputTokens < 32 || overflowInputTokens > targetInputTokens) {
            throw new IllegalArgumentException("overflow tokens must be within the target budget");
        }
        if (recentTurns < 1) {
            throw new IllegalArgumentException("at least one recent turn must be retained");
        }
        compactionMode = compactionMode == null ? ContextCompactionMode.SYNC : compactionMode;
        compactionTimeout = compactionTimeout == null ? Duration.ofMillis(500) : compactionTimeout;
        if (compactionTimeout.isNegative() || compactionTimeout.isZero()) {
            throw new IllegalArgumentException("compaction timeout must be positive");
        }
        if (overflowRetryLimit < 0 || overflowRetryLimit > 3) {
            throw new IllegalArgumentException("overflow retry limit must be between 0 and 3");
        }
    }

    public int budget(ContextAssemblyMode mode) {
        return mode == ContextAssemblyMode.OVERFLOW_FALLBACK
                ? overflowInputTokens : targetInputTokens;
    }
}
