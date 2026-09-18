package io.seekflux.platform.agentruntime.domain.model.context;

import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ContextMessage;
import java.util.List;

public record ContextLayer(
        Type type,
        boolean protectedLayer,
        List<ContextMessage> messages) {

    public ContextLayer {
        if (type == null) {
            throw new IllegalArgumentException("context layer type is required");
        }
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    public enum Type {
        STABLE_PREFIX,
        AGENT_INSTRUCTIONS,
        EPHEMERAL_SKILL_INSTRUCTIONS,
        ACTIVE_SKILL_INSTRUCTIONS,
        SKILL_CATALOG,
        DYNAMIC_CAPABILITIES,
        WORKSPACE_STATE,
        COMPACTION_SUMMARY,
        HISTORY,
        CURRENT_RECALL
    }
}
