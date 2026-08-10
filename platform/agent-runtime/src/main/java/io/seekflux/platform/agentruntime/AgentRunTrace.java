package io.seekflux.platform.agentruntime;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.seekflux.platform.agentruntime.llm.LlmUsage;

public record AgentRunTrace(
        String agentRunId,
        String requestId,
        String sessionId,
        String turnId,
        DefinitionSnapshot definition,
        Instant startedAt,
        long tookMillis,
        AgentTerminalState terminalState,
        String executionMode,
        String fallbackReason,
        LlmUsage llmUsage,
        List<StepTrace> steps) {

    public AgentRunTrace(
            String agentRunId,
            String requestId,
            String sessionId,
            String turnId,
            DefinitionSnapshot definition,
            Instant startedAt,
            long tookMillis,
            AgentTerminalState terminalState,
            String executionMode,
            String fallbackReason,
            List<StepTrace> steps) {
        this(agentRunId, requestId, sessionId, turnId, definition, startedAt, tookMillis,
                terminalState, executionMode, fallbackReason, LlmUsage.UNMEASURED, steps);
    }

    public AgentRunTrace {
        llmUsage = llmUsage == null ? LlmUsage.UNMEASURED : llmUsage;
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (tookMillis < 0) {
            throw new IllegalArgumentException("agent timing must not be negative");
        }
    }

    public record DefinitionSnapshot(
            String id,
            String version,
            String plannerVersion,
            String promptVersion,
            String decisionProviderVersion,
            int maxSteps,
            int maxToolCalls,
            long timeoutMillis,
            Map<String, String> toolSchemaVersions) {

        public DefinitionSnapshot {
            toolSchemaVersions = Map.copyOf(toolSchemaVersions);
        }
    }

    public record StepTrace(
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
