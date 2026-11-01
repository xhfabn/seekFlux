package io.seekflux.platform.agentruntime.autoconfigure;

import io.seekflux.platform.agentruntime.domain.model.context.ContextCompactionMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("seekflux.agent.runtime")
public class AgentRuntimeProperties {

    private boolean enabled = true;
    private int executionThreads = 8;
    private int executionQueueCapacity = 64;
    private int contextThreads = 2;
    private int contextQueueCapacity = 16;
    private int maxConcurrentModelCalls = 4;
    private int maxConcurrentToolCalls = 8;
    private Duration cancellationPollInterval = Duration.ofMillis(100);
    private Duration shutdownGracePeriod = Duration.ofSeconds(5);
    private int steerQueueMaxDepth = 32;
    private final Context context = new Context();
    private final Observation observation = new Observation();

    public Observation getObservation() { return observation; }

    public void validate() {
        requirePositive(executionThreads, "execution-threads");
        requirePositive(executionQueueCapacity, "execution-queue-capacity");
        requirePositive(contextThreads, "context-threads");
        requirePositive(contextQueueCapacity, "context-queue-capacity");
        requirePositive(maxConcurrentModelCalls, "max-concurrent-model-calls");
        requirePositive(maxConcurrentToolCalls, "max-concurrent-tool-calls");
        requirePositive(steerQueueMaxDepth, "steer-queue-max-depth");
        requireNonNegative(cancellationPollInterval, "cancellation-poll-interval");
        requirePositive(shutdownGracePeriod, "shutdown-grace-period");
        context.validate();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getExecutionThreads() {
        return executionThreads;
    }

    public void setExecutionThreads(int executionThreads) {
        this.executionThreads = executionThreads;
    }

    public int getExecutionQueueCapacity() {
        return executionQueueCapacity;
    }

    public void setExecutionQueueCapacity(int executionQueueCapacity) {
        this.executionQueueCapacity = executionQueueCapacity;
    }

    public int getContextThreads() {
        return contextThreads;
    }

    public void setContextThreads(int contextThreads) {
        this.contextThreads = contextThreads;
    }

    public int getContextQueueCapacity() {
        return contextQueueCapacity;
    }

    public void setContextQueueCapacity(int contextQueueCapacity) {
        this.contextQueueCapacity = contextQueueCapacity;
    }

    public int getMaxConcurrentModelCalls() {
        return maxConcurrentModelCalls;
    }

    public void setMaxConcurrentModelCalls(int maxConcurrentModelCalls) {
        this.maxConcurrentModelCalls = maxConcurrentModelCalls;
    }

    public int getMaxConcurrentToolCalls() {
        return maxConcurrentToolCalls;
    }

    public void setMaxConcurrentToolCalls(int maxConcurrentToolCalls) {
        this.maxConcurrentToolCalls = maxConcurrentToolCalls;
    }

    public Duration getCancellationPollInterval() {
        return cancellationPollInterval;
    }

    public void setCancellationPollInterval(Duration cancellationPollInterval) {
        this.cancellationPollInterval = cancellationPollInterval;
    }

    public Duration getShutdownGracePeriod() {
        return shutdownGracePeriod;
    }

    public void setShutdownGracePeriod(Duration shutdownGracePeriod) {
        this.shutdownGracePeriod = shutdownGracePeriod;
    }

    public int getSteerQueueMaxDepth() {
        return steerQueueMaxDepth;
    }

    public void setSteerQueueMaxDepth(int steerQueueMaxDepth) {
        this.steerQueueMaxDepth = steerQueueMaxDepth;
    }

    public Context getContext() {
        return context;
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("seekflux.agent.runtime." + name + " must be positive");
        }
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("seekflux.agent.runtime." + name + " must be positive");
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        if (value == null || value.isNegative()) {
            throw new IllegalArgumentException(
                    "seekflux.agent.runtime." + name + " must not be negative");
        }
    }

    public static class Context {
        private int maxInputTokens = 8_192;
        private int targetInputTokens = 6_144;
        private int overflowInputTokens = 4_096;
        private int recentTurns = 2;
        private ContextCompactionMode compactionMode = ContextCompactionMode.SYNC;
        private Duration compactionTimeout = Duration.ofMillis(500);
        private int overflowRetryLimit = 1;

        void validate() {
            if (maxInputTokens < 128) {
                throw new IllegalArgumentException("context.max-input-tokens must be at least 128");
            }
            if (targetInputTokens < 64 || targetInputTokens > maxInputTokens) {
                throw new IllegalArgumentException("context.target-input-tokens is outside the maximum");
            }
            if (overflowInputTokens < 32 || overflowInputTokens > targetInputTokens) {
                throw new IllegalArgumentException("context.overflow-input-tokens is outside the target");
            }
            requirePositive(recentTurns, "context.recent-turns");
            requirePositive(compactionTimeout, "context.compaction-timeout");
            if (overflowRetryLimit < 0 || overflowRetryLimit > 3) {
                throw new IllegalArgumentException("context.overflow-retry-limit must be between 0 and 3");
            }
        }

        public int getMaxInputTokens() {
            return maxInputTokens;
        }

        public void setMaxInputTokens(int maxInputTokens) {
            this.maxInputTokens = maxInputTokens;
        }

        public int getTargetInputTokens() {
            return targetInputTokens;
        }

        public void setTargetInputTokens(int targetInputTokens) {
            this.targetInputTokens = targetInputTokens;
        }

        public int getOverflowInputTokens() {
            return overflowInputTokens;
        }

        public void setOverflowInputTokens(int overflowInputTokens) {
            this.overflowInputTokens = overflowInputTokens;
        }

        public int getRecentTurns() {
            return recentTurns;
        }

        public void setRecentTurns(int recentTurns) {
            this.recentTurns = recentTurns;
        }

        public ContextCompactionMode getCompactionMode() {
            return compactionMode;
        }

        public void setCompactionMode(ContextCompactionMode compactionMode) {
            this.compactionMode = compactionMode;
        }

        public Duration getCompactionTimeout() {
            return compactionTimeout;
        }

        public void setCompactionTimeout(Duration compactionTimeout) {
            this.compactionTimeout = compactionTimeout;
        }

        public int getOverflowRetryLimit() {
            return overflowRetryLimit;
        }

        public void setOverflowRetryLimit(int overflowRetryLimit) {
            this.overflowRetryLimit = overflowRetryLimit;
        }
    }
    public static class Observation {
        private boolean enabled;
        private boolean captureContent;
        private int queueCapacity = 2048;
        private int batchSize = 64;
        private int maxAttempts = 3;
        private int maxCharacters = 16384;
        private int maxNodes = 512;
        private int maxDepth = 12;
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private java.util.Set<String> redactedFields = new java.util.HashSet<>(java.util.Set.of(
                "authorization", "password", "secret", "apikey", "api_key", "access_token"));
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public boolean isCaptureContent() { return captureContent; }
        public void setCaptureContent(boolean value) { captureContent = value; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int value) { queueCapacity = value; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = value; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { maxAttempts = value; }
        public int getMaxCharacters() { return maxCharacters; }
        public void setMaxCharacters(int value) { maxCharacters = value; }
        public int getMaxNodes() { return maxNodes; }
        public void setMaxNodes(int value) { maxNodes = value; }
        public int getMaxDepth() { return maxDepth; }
        public void setMaxDepth(int value) { maxDepth = value; }
        public Duration getShutdownTimeout() { return shutdownTimeout; }
        public void setShutdownTimeout(Duration value) { shutdownTimeout = value; }
        public java.util.Set<String> getRedactedFields() { return redactedFields; }
        public void setRedactedFields(java.util.Set<String> value) { redactedFields = value; }
    }

}
