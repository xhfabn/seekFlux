package io.seekflux.platform.agentruntime.llm;

import io.seekflux.platform.agentruntime.AgentDecision;

public record LlmCallResult(AgentDecision decision, LlmUsage usage) {

    public LlmCallResult {
        if (decision == null) {
            throw new IllegalArgumentException("LLM decision must not be null");
        }
        usage = usage == null ? LlmUsage.UNMEASURED : usage;
    }
}
