package io.seekflux.platform.agentruntime.application.spi.capability.event.model;

import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import java.time.Instant;

public sealed interface PushEvent {

    String agentRunId();

    Instant eventTime();

    record LoopStarted(String agentRunId, Instant eventTime, String agentId) implements PushEvent {
    }

    record SegmentStarted(
            String agentRunId,
            Instant eventTime,
            String requestId,
            String turnId) implements PushEvent {
    }

    record LlmTurnStarted(
            String agentRunId,
            Instant eventTime,
            int step,
            String providerVersion) implements PushEvent {
    }

    record ContentDelta(
            String agentRunId,
            Instant eventTime,
            int step,
            long chunkSequence,
            String delta) implements PushEvent {
    }

    record ReasoningDelta(
            String agentRunId,
            Instant eventTime,
            int step,
            long chunkSequence,
            String delta) implements PushEvent {
    }

    record ToolCallDelta(
            String agentRunId,
            Instant eventTime,
            int step,
            long chunkSequence,
            int toolIndex,
            String toolCallIdDelta,
            String toolNameDelta,
            String argumentsDelta,
            boolean argumentsComplete) implements PushEvent {
    }

    record LlmTurnCompleted(
            String agentRunId,
            Instant eventTime,
            int step,
            String finishReason,
            long inputTokens,
            long outputTokens,
            long cachedInputTokens,
            long reasoningTokens) implements PushEvent {
    }

    record ToolStarted(
            String agentRunId,
            Instant eventTime,
            String toolCallId,
            String toolName,
            int toolIndex,
            boolean eager) implements PushEvent {
    }

    record CheckpointSaved(
            String agentRunId,
            Instant eventTime,
            String boundary,
            int nextStep) implements PushEvent {
    }

    record Control(
            String agentRunId,
            Instant eventTime,
            String code,
            String detail) implements PushEvent {
    }

    record ToolCompleted(
            String agentRunId,
            Instant eventTime,
            String toolCallId,
            String toolName,
            String status,
            String linkedTraceId) implements PushEvent {
    }

    record LoopCompleted(
            String agentRunId,
            Instant eventTime,
            AgentTerminalState state,
            long tookMillis,
            String cancellationReason) implements PushEvent {

        public LoopCompleted(
                String agentRunId,
                Instant eventTime,
                AgentTerminalState state,
                long tookMillis) {
            this(agentRunId, eventTime, state, tookMillis, null);
        }
    }

    record RuntimeError(
            String agentRunId,
            Instant eventTime,
            String errorCode) implements PushEvent {
    }

    record MessageQueued(
            String agentRunId,
            Instant eventTime,
            String messageId,
            String requestId,
            String turnId,
            int queueDepth) implements PushEvent {
    }

    record Steered(
            String agentRunId,
            Instant eventTime,
            String nextRequestId,
            int drainedMessageCount) implements PushEvent {
    }

    record WaitSuspended(
            String agentRunId,
            Instant eventTime,
            WaitState waitState) implements PushEvent {
    }

    record WaitResolved(
            String agentRunId,
            Instant eventTime,
            WaitResolution resolution) implements PushEvent {
    }
}
