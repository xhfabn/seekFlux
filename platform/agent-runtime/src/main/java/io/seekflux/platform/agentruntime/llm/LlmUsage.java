package io.seekflux.platform.agentruntime.llm;

public record LlmUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long costMicros,
        boolean measured) {

    public static final LlmUsage UNMEASURED = new LlmUsage(0, 0, 0, 0, false);

    public LlmUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens < 0 || costMicros < 0) {
            throw new IllegalArgumentException("LLM usage values must not be negative");
        }
    }

    public LlmUsage plus(LlmUsage other) {
        return new LlmUsage(
                inputTokens + other.inputTokens,
                outputTokens + other.outputTokens,
                totalTokens + other.totalTokens,
                costMicros + other.costMicros,
                measured || other.measured);
    }
}
