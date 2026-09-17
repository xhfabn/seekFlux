package io.seekflux.platform.agentruntime.domain.service.llm;

import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ChatChunk;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChatStreamAssembler {

    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private final Map<Integer, MutableToolCall> toolCalls = new LinkedHashMap<>();
    private long expectedSequence;
    private LlmUsage usage = LlmUsage.UNMEASURED;
    private String finishReason;
    private boolean terminal;
    private boolean outputStarted;

    public synchronized void accept(ChatChunk chunk) {
        if (terminal) {
            throw new IllegalStateException("chat stream already terminated");
        }
        if (chunk.sequence() != expectedSequence) {
            throw new IllegalStateException(
                    "chat chunk sequence gap: expected " + expectedSequence
                            + " but got " + chunk.sequence());
        }
        expectedSequence++;
        content.append(chunk.contentDelta());
        reasoning.append(chunk.reasoningDelta());
        if (chunk.toolCallDelta() != null) {
            ChatChunk.ToolCallDelta delta = chunk.toolCallDelta();
            toolCalls.computeIfAbsent(delta.index(), ignored -> new MutableToolCall(delta.index()))
                    .append(delta);
        }
        if (chunk.usage().measured()) {
            usage = chunk.usage();
        }
        if (chunk.finishReason() != null && !chunk.finishReason().isBlank()) {
            finishReason = chunk.finishReason();
        }
        outputStarted |= chunk.hasOutput();
        terminal = chunk.terminal();
    }

    public synchronized Assembly finish() {
        if (!terminal) {
            throw new IllegalStateException("chat stream did not terminate");
        }
        return new Assembly(
                content.toString(),
                reasoning.toString(),
                toolCalls.values().stream()
                        .sorted(Comparator.comparingInt(MutableToolCall::index))
                        .map(MutableToolCall::snapshot)
                        .toList(),
                usage,
                finishReason,
                outputStarted);
    }

    public synchronized boolean outputStarted() {
        return outputStarted;
    }

    public synchronized List<ToolCall> toolCalls() {
        return toolCalls.values().stream()
                .sorted(Comparator.comparingInt(MutableToolCall::index))
                .map(MutableToolCall::snapshot)
                .toList();
    }

    private static final class MutableToolCall {
        private final int index;
        private final StringBuilder id = new StringBuilder();
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
        private boolean argumentsComplete;

        private MutableToolCall(int index) {
            this.index = index;
        }

        private void append(ChatChunk.ToolCallDelta delta) {
            id.append(delta.idDelta());
            name.append(delta.nameDelta());
            arguments.append(delta.argumentsDelta());
            argumentsComplete |= delta.argumentsComplete();
        }

        private int index() {
            return index;
        }

        private ToolCall snapshot() {
            return new ToolCall(index, id.toString(), name.toString(),
                    arguments.toString(), argumentsComplete);
        }
    }

    public record Assembly(
            String content,
            String reasoning,
            List<ToolCall> toolCalls,
            LlmUsage usage,
            String finishReason,
            boolean outputStarted) {

        public Assembly {
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            usage = usage == null ? LlmUsage.UNMEASURED : usage;
        }
    }

    public record ToolCall(
            int index,
            String id,
            String name,
            String argumentsJson,
            boolean argumentsComplete) {
    }
}
