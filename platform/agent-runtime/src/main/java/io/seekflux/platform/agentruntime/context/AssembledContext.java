package io.seekflux.platform.agentruntime.context;

import io.seekflux.platform.agentruntime.AgentDecisionContext;
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
