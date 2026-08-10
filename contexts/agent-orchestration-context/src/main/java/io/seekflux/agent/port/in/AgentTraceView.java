package io.seekflux.agent.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record AgentTraceView(
        String agentRunId,
        String agentId,
        String agentVersion,
        String plannerVersion,
        String promptVersion,
        String decisionProviderVersion,
        Map<String, String> toolSchemaVersions,
        Instant startedAt,
        long tookMillis,
        String terminalState,
        String executionMode,
        String fallbackReason,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long costMicros,
        boolean usageMeasured,
        List<StepView> steps) {

    public AgentTraceView {
        toolSchemaVersions = toolSchemaVersions == null ? Map.of() : Map.copyOf(toolSchemaVersions);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record StepView(
            int step,
            String action,
            String status,
            String toolCallId,
            String toolName,
            String linkedTraceId,
            long tookMillis,
            String errorCode) {
    }
}
