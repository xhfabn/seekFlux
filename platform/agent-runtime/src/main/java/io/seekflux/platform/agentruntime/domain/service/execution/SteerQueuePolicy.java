package io.seekflux.platform.agentruntime.domain.service.execution;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SteerQueuePolicy {

    public static final int DEFAULT_MAX_DEPTH = 32;
    private static final Set<String> FORBIDDEN_OVERRIDE_KEYS = Set.of(
            "agentdefpatch",
            "agentoverride",
            "executionmanifest",
            "chainedagentpatches",
            "evaluationoverride",
            "llmoverride",
            "modeloverride",
            "promptoverride");

    private final int maxDepth;

    public SteerQueuePolicy(int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("steer queue max depth must be positive");
        }
        this.maxDepth = maxDepth;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public AgentRunRequest sanitize(AgentRunRequest request) {
        return request.withAttributes(sanitizeMap(request.attributes()));
    }

    public Map<String, Object> sanitizeFeatures(Map<String, Object> features) {
        return sanitizeMap(features == null ? Map.of() : features);
    }

    private static Map<String, Object> sanitizeMap(Map<String, Object> source) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = key.replace("_", "")
                    .replace("-", "")
                    .toLowerCase(Locale.ROOT);
            if (!FORBIDDEN_OVERRIDE_KEYS.contains(normalized)) {
                sanitized.put(key, value);
            }
        });
        return Map.copyOf(sanitized);
    }
}
