package io.seekflux.platform.agentruntime.event;

import io.seekflux.platform.agentruntime.AgentTerminalState;
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
            long tookMillis) implements PushEvent {
    }

    record RuntimeError(
            String agentRunId,
            Instant eventTime,
            String errorCode) implements PushEvent {
    }
}
