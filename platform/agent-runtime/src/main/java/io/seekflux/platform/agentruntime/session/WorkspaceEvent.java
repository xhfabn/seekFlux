package io.seekflux.platform.agentruntime.session;

import io.seekflux.platform.agentruntime.AgentTerminalState;
import java.time.Instant;

public sealed interface WorkspaceEvent {

    long position();

    Instant eventTime();

    record SessionCreated(
            long position,
            Instant eventTime,
            String agentId,
            String agentVersion) implements WorkspaceEvent {
    }

    record UserMessage(
            long position,
            Instant eventTime,
            String requestId,
            String turnId,
            String text) implements WorkspaceEvent {
    }

    record RunCompleted(
            long position,
            Instant eventTime,
            String agentRunId,
            AgentTerminalState state,
            String fallbackReason) implements WorkspaceEvent {
    }

    record RunCancelled(
            long position,
            Instant eventTime,
            String agentRunId,
            String reason) implements WorkspaceEvent {
    }

    record RunFailed(
            long position,
            Instant eventTime,
            String agentRunId,
            String errorCode) implements WorkspaceEvent {
    }
}
