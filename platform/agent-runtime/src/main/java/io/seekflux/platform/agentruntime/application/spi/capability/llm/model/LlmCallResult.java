package io.seekflux.platform.agentruntime.application.spi.capability.llm.model;

import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;

public record LlmCallResult(AgentDecision decision, LlmUsage usage) {

    public LlmCallResult {
        if (decision == null) {
            throw new IllegalArgumentException("LLM decision must not be null");
        }
        usage = usage == null ? LlmUsage.UNMEASURED : usage;
    }
}
