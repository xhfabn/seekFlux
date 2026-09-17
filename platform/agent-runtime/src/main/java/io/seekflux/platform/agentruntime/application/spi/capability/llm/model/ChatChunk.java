package io.seekflux.platform.agentruntime.application.spi.capability.llm.model;

import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;

public record ChatChunk(
        long sequence,
        String contentDelta,
        String reasoningDelta,
        ToolCallDelta toolCallDelta,
        LlmUsage usage,
        String finishReason,
        boolean terminal) {

    public ChatChunk {
        if (sequence < 0) {
            throw new IllegalArgumentException("chat chunk sequence must not be negative");
        }
        contentDelta = contentDelta == null ? "" : contentDelta;
        reasoningDelta = reasoningDelta == null ? "" : reasoningDelta;
        usage = usage == null ? LlmUsage.UNMEASURED : usage;
    }

    public boolean hasOutput() {
        return !contentDelta.isEmpty() || !reasoningDelta.isEmpty() || toolCallDelta != null;
    }

    public record ToolCallDelta(
            int index,
            String idDelta,
            String nameDelta,
            String argumentsDelta,
            boolean argumentsComplete) {

        public ToolCallDelta {
            if (index < 0) {
                throw new IllegalArgumentException("tool call delta index must not be negative");
            }
            idDelta = idDelta == null ? "" : idDelta;
            nameDelta = nameDelta == null ? "" : nameDelta;
            argumentsDelta = argumentsDelta == null ? "" : argumentsDelta;
        }
    }
}
