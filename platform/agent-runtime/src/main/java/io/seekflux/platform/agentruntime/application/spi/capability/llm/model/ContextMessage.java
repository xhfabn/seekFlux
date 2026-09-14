package io.seekflux.platform.agentruntime.application.spi.capability.llm.model;

public record ContextMessage(
        String role,
        String content,
        String messageId,
        String toolCallId,
        String toolName) {

    public ContextMessage(String role, String content) {
        this(role, content, null, null, null);
    }
}
