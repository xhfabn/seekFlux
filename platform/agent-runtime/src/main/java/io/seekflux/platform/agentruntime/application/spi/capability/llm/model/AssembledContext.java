package io.seekflux.platform.agentruntime.application.spi.capability.llm.model;

import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.domain.model.context.ContextAssemblyMode;
import java.util.List;

public record AssembledContext(
        AgentDecisionContext decisionContext,
        List<ContextMessage> messages,
        String specId,
        int estimatedTokens,
        int budgetTokens,
        boolean compacted,
        long compactionCutoff,
        ContextAssemblyMode assemblyMode,
        List<ChatToolDefinition> tools) {

    public AssembledContext(
            AgentDecisionContext decisionContext,
            List<ContextMessage> messages,
            String specId,
            int estimatedTokens) {
        this(decisionContext, messages, specId, estimatedTokens,
                Integer.MAX_VALUE, false, 0, ContextAssemblyMode.NORMAL, List.of());
    }

    public AssembledContext(
            AgentDecisionContext decisionContext,
            List<ContextMessage> messages,
            String specId,
            int estimatedTokens,
            int budgetTokens,
            boolean compacted,
            long compactionCutoff,
            ContextAssemblyMode assemblyMode) {
        this(decisionContext, messages, specId, estimatedTokens, budgetTokens,
                compacted, compactionCutoff, assemblyMode, List.of());
    }

    public AssembledContext {
        messages = messages == null ? List.of() : List.copyOf(messages);
        assemblyMode = assemblyMode == null ? ContextAssemblyMode.NORMAL : assemblyMode;
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
