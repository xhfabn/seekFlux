package io.seekflux.platform.agentruntime.application.spi.capability.tool;

import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;

@FunctionalInterface
public interface ToolExecutionObserver {

    ToolExecutionObserver NOOP = event -> { };

    void observe(Event event);

    record Event(
            Phase phase,
            Source source,
            String toolCallId,
            String toolName,
            AgentTool.Effect effect,
            String attemptId,
            long durationMillis,
            String outcome) {
    }

    enum Phase { BEFORE, AFTER, FAILURE }

    enum Source { INITIAL, RECOVERY, RECONCILIATION }
}
