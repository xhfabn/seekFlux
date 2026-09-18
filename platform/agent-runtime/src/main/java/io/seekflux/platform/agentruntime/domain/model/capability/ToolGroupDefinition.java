package io.seekflux.platform.agentruntime.domain.model.capability;

import java.util.Set;

public record ToolGroupDefinition(
        String groupId,
        String version,
        String description,
        Set<String> toolIds,
        boolean alwaysActive) {

    public ToolGroupDefinition {
        groupId = requireText(groupId, "ToolGroup id", 128);
        version = requireText(version, "ToolGroup version", 128);
        description = requireText(description, "ToolGroup description", 512);
        if (toolIds == null || toolIds.isEmpty()) {
            throw new IllegalArgumentException("a ToolGroup must contain at least one Tool");
        }
        if (toolIds.size() > 128) {
            throw new IllegalArgumentException("ToolGroup Tool count must not exceed 128");
        }
        java.util.LinkedHashSet<String> normalized = new java.util.LinkedHashSet<>();
        for (String toolId : toolIds) {
            normalized.add(requireText(toolId, "Tool id", 128));
        }
        toolIds = Set.copyOf(normalized);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
