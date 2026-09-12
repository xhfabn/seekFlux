package io.seekflux.platform.agentruntime.application.spi.capability.llm.model;

import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import java.util.List;

public record AssembledContext(
        AgentDecisionContext decisionContext,
        List<ContextMessage> messages,
        String specId,
        int estimatedTokens) {

    public AssembledContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
