package io.seekflux.platform.agentruntime.domain.model.capability;

import java.util.LinkedHashSet;
import java.util.Set;

public record CapabilityChange(
        int schemaVersion,
        String operationId,
        long baseVersion,
        Set<String> activateSkills,
        Set<String> deactivateSkills,
        Set<String> activateToolGroups,
        Set<String> deactivateToolGroups) {

    public CapabilityChange {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("capability change schema version must be positive");
        }
        if (operationId == null || operationId.isBlank() || operationId.length() > 128) {
            throw new IllegalArgumentException("capability operation id must contain at most 128 characters");
        }
        if (baseVersion < 0) {
            throw new IllegalArgumentException("capability base version must not be negative");
        }
        activateSkills = copy(activateSkills);
        deactivateSkills = copy(deactivateSkills);
        activateToolGroups = copy(activateToolGroups);
        deactivateToolGroups = copy(deactivateToolGroups);
        if (!java.util.Collections.disjoint(activateSkills, deactivateSkills)
                || !java.util.Collections.disjoint(activateToolGroups, deactivateToolGroups)) {
            throw new IllegalArgumentException("a capability cannot be activated and deactivated together");
        }
        if (activateSkills.isEmpty() && deactivateSkills.isEmpty()
                && activateToolGroups.isEmpty() && deactivateToolGroups.isEmpty()) {
            throw new IllegalArgumentException("a capability change must contain at least one operation");
        }
    }

    public CapabilityActivationState apply(CapabilityActivationState current) {
        if (current.version() != baseVersion) {
            throw new IllegalArgumentException("capability activation base version does not match");
        }
        Set<String> skills = new LinkedHashSet<>(current.activeSkills());
        skills.removeAll(deactivateSkills);
        skills.addAll(activateSkills);
        Set<String> groups = new LinkedHashSet<>(current.activeToolGroups());
        groups.removeAll(deactivateToolGroups);
        groups.addAll(activateToolGroups);
        return new CapabilityActivationState(baseVersion + 1, skills, groups);
    }

    private static Set<String> copy(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        if (values.size() > 64) {
            throw new IllegalArgumentException("capability change item count must not exceed 64");
        }
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException("capability id must contain at most 128 characters");
            }
            result.add(value.trim());
        }
        return Set.copyOf(result);
    }
}
