package io.seekflux.platform.agentruntime.domain.model.capability;

import java.util.Set;

public record CapabilityActivationState(
        long version,
        Set<String> activeSkills,
        Set<String> activeToolGroups) {

    public static final CapabilityActivationState EMPTY = new CapabilityActivationState(0, Set.of(), Set.of());

    public CapabilityActivationState {
        if (version < 0) {
            throw new IllegalArgumentException("capability activation version must not be negative");
        }
        activeSkills = normalized(activeSkills, "active Skill");
        activeToolGroups = normalized(activeToolGroups, "active ToolGroup");
    }

    private static Set<String> normalized(Set<String> values, String name) {
        if (values == null) {
            return Set.of();
        }
        if (values.size() > 64) {
            throw new IllegalArgumentException(name + " count must not exceed 64");
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException(
                        name + " must contain at most 128 characters");
            }
            result.add(value.trim());
        }
        return Set.copyOf(result);
    }
}
