package io.seekflux.platform.agentruntime;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public record AgentDefinition(
        String id,
        String version,
        String plannerVersion,
        String promptVersion,
        String decisionProviderVersion,
        Set<String> allowedTools,
        int maxSteps,
        int maxToolCalls,
        Duration timeout,
        boolean fallbackEnabled) {

    public AgentDefinition {
        id = requireText(id, "agent id");
        version = requireText(version, "agent version");
        plannerVersion = requireText(plannerVersion, "planner version");
        promptVersion = requireText(promptVersion, "prompt version");
        decisionProviderVersion = requireText(decisionProviderVersion, "decision provider version");
        allowedTools = Set.copyOf(Objects.requireNonNull(allowedTools, "allowed tools must not be null"));
        if (allowedTools.isEmpty()) {
            throw new IllegalArgumentException("an agent must allow at least one tool");
        }
        if (maxSteps < 1 || maxSteps > 20) {
            throw new IllegalArgumentException("max steps must be between 1 and 20");
        }
        if (maxToolCalls < 1 || maxToolCalls > maxSteps) {
            throw new IllegalArgumentException("max tool calls must be between 1 and max steps");
        }
        Objects.requireNonNull(timeout, "agent timeout must not be null");
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("agent timeout must be between 1 nanosecond and 30 seconds");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
