package io.seekflux.platform.agentruntime.application.spi.capability.event.model;

import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import java.time.Instant;

public sealed interface PushEvent {

    String agentRunId();

    Instant eventTime();

    record LoopStarted(String agentRunId, Instant eventTime, String agentId) implements PushEvent {
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
}
