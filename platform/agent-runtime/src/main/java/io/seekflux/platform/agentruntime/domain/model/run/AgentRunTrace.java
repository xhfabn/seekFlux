package io.seekflux.platform.agentruntime.domain.model.run;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilitySnapshot;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolDefinition;

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
        String cancellationReason,
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
            LlmUsage llmUsage,
            List<StepTrace> steps) {
        this(agentRunId, requestId, sessionId, turnId, definition, startedAt, tookMillis,
                terminalState, executionMode, fallbackReason, null, llmUsage, steps);
    }

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
                terminalState, executionMode, fallbackReason, null, LlmUsage.UNMEASURED, steps);
    }

    public AgentRunTrace {
        llmUsage = llmUsage == null ? LlmUsage.UNMEASURED : llmUsage;
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (tookMillis < 0) {
            throw new IllegalArgumentException("agent timing must not be negative");
        }
        if (terminalState == AgentTerminalState.CANCELLED && cancellationReason == null) {
            throw new IllegalArgumentException("a cancelled trace must have a cancellation reason");
        }
        if (terminalState != AgentTerminalState.CANCELLED && cancellationReason != null) {
            throw new IllegalArgumentException("only a cancelled trace can have a cancellation reason");
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
            Map<String, String> toolSchemaVersions,
            CapabilitySnapshot capabilities,
            Map<String, AgentToolDefinition> toolDefinitions) {

        public DefinitionSnapshot(String id, String version, String plannerVersion, String promptVersion,
                String decisionProviderVersion, int maxSteps, int maxToolCalls, long timeoutMillis,
                Map<String, String> toolSchemaVersions, CapabilitySnapshot capabilities) {
            this(id, version, plannerVersion, promptVersion, decisionProviderVersion, maxSteps, maxToolCalls,
                    timeoutMillis, toolSchemaVersions, capabilities, Map.of());
        }

        public DefinitionSnapshot(
                String id,
                String version,
                String plannerVersion,
                String promptVersion,
                String decisionProviderVersion,
                int maxSteps,
                int maxToolCalls,
                long timeoutMillis,
                Map<String, String> toolSchemaVersions) {
            this(id, version, plannerVersion, promptVersion, decisionProviderVersion,
                    maxSteps, maxToolCalls, timeoutMillis, toolSchemaVersions,
                    CapabilitySnapshot.legacy(toolSchemaVersions == null
                            ? java.util.Set.of() : toolSchemaVersions.keySet()));
        }

        public DefinitionSnapshot {
            toolSchemaVersions = toolSchemaVersions == null ? Map.of() : Map.copyOf(toolSchemaVersions);
            toolDefinitions = toolDefinitions == null ? Map.of() : Map.copyOf(toolDefinitions);
            if (!toolDefinitions.isEmpty() && !toolDefinitions.keySet().equals(toolSchemaVersions.keySet())) {
                throw new IllegalArgumentException("frozen Tool definitions must match Tool versions");
            }
            for (var entry : toolDefinitions.entrySet()) {
                if (!entry.getKey().equals(entry.getValue().name())
                        || !entry.getValue().schema().version().equals(toolSchemaVersions.get(entry.getKey()))) {
                    throw new IllegalArgumentException("frozen Tool definition identity/version mismatch");
                }
            }
            capabilities = capabilities == null
                    ? CapabilitySnapshot.legacy(toolSchemaVersions.keySet()) : capabilities;
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
