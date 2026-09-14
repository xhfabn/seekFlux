package io.seekflux.platform.agentruntime.application.spi.capability.llm.model;

import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.message.AgentAssistantContent;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;

public record LlmCallResult(
        AgentDecision decision,
        LlmUsage usage,
        AgentAssistantContent assistantContent) {

    public LlmCallResult(AgentDecision decision, LlmUsage usage) {
        this(decision, usage, AgentAssistantContent.EMPTY);
    }

    public LlmCallResult {
        if (decision == null) {
            throw new IllegalArgumentException("LLM decision must not be null");
        }
        usage = usage == null ? LlmUsage.UNMEASURED : usage;
        assistantContent = assistantContent == null ? AgentAssistantContent.EMPTY : assistantContent;
    }
}
