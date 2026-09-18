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
        String capabilityCatalogVersion,
        String capabilityFingerprint,
        Map<String, String> skillVersions,
        Map<String, String> toolGroupVersions,
        List<String> activeSkills,
        List<String> activeToolGroups,
        List<String> effectiveTools,
        Instant startedAt,
        long tookMillis,
        String terminalState,
        String executionMode,
        String fallbackReason,
        String cancellationReason,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long costMicros,
        long cachedInputTokens,
        long reasoningTokens,
        boolean usageMeasured,
        List<StepView> steps) {

    public AgentTraceView {
        toolSchemaVersions = toolSchemaVersions == null ? Map.of() : Map.copyOf(toolSchemaVersions);
        skillVersions = skillVersions == null ? Map.of() : Map.copyOf(skillVersions);
        toolGroupVersions = toolGroupVersions == null ? Map.of() : Map.copyOf(toolGroupVersions);
        activeSkills = activeSkills == null ? List.of() : List.copyOf(activeSkills);
        activeToolGroups = activeToolGroups == null ? List.of() : List.copyOf(activeToolGroups);
        effectiveTools = effectiveTools == null ? List.of() : List.copyOf(effectiveTools);
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
