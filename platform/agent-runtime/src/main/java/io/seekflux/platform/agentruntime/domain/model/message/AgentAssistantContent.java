package io.seekflux.platform.agentruntime.domain.model.message;

public record AgentAssistantContent(
        String content,
        String reasoning,
        boolean reasoningReplayable) {

    public static final AgentAssistantContent EMPTY = new AgentAssistantContent(null, null, false);

    public AgentAssistantContent {
        content = normalize(content);
        reasoning = normalize(reasoning);
        if (reasoning == null) {
            reasoningReplayable = false;
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
