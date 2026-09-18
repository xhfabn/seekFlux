package io.seekflux.platform.agentruntime.application.command;

import io.seekflux.platform.agentruntime.domain.model.capability.SkillDefinition;
import java.util.List;
import java.util.Set;

public record CapabilityRequest(
        int schemaVersion,
        List<SkillDefinition> ephemeralSkills,
        Set<String> activateToolGroups,
        Set<String> deactivateToolGroups,
        boolean toolRestrictionEnabled,
        Set<String> requestedTools) {

    public static final CapabilityRequest NONE = new CapabilityRequest(
            1, List.of(), Set.of(), Set.of(), false, Set.of());

    public CapabilityRequest {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("capability request schema version must be positive");
        }
        ephemeralSkills = ephemeralSkills == null ? List.of() : List.copyOf(ephemeralSkills);
        if (ephemeralSkills.size() > 16) {
            throw new IllegalArgumentException("ephemeral Skill count must not exceed 16");
        }
        java.util.HashSet<String> ephemeralIds = new java.util.HashSet<>();
        for (SkillDefinition skill : ephemeralSkills) {
            if (skill.source() != SkillDefinition.Source.REQUEST) {
                throw new IllegalArgumentException("an ephemeral Skill must have REQUEST source");
            }
            if (!ephemeralIds.add(skill.skillId())) {
                throw new IllegalArgumentException(
                        "duplicate ephemeral Skill id: " + skill.skillId());
            }
        }
        activateToolGroups = normalized(activateToolGroups, "activated ToolGroup", 64);
        deactivateToolGroups = normalized(deactivateToolGroups, "deactivated ToolGroup", 64);
        requestedTools = normalized(requestedTools, "requested Tool", 128);
        if (!java.util.Collections.disjoint(activateToolGroups, deactivateToolGroups)) {
            throw new IllegalArgumentException("a ToolGroup cannot be activated and deactivated together");
        }
        if (toolRestrictionEnabled && requestedTools.isEmpty()) {
            throw new IllegalArgumentException("an enabled Tool restriction must not be empty");
        }
    }

    public static CapabilityRequest restrictTools(java.util.Collection<String> tools) {
        return new CapabilityRequest(1, List.of(), Set.of(), Set.of(), true, Set.copyOf(tools));
    }

    private static Set<String> normalized(Set<String> values, String name, int maxItems) {
        if (values == null) {
            return Set.of();
        }
        if (values.size() > maxItems) {
            throw new IllegalArgumentException(name + " count must not exceed " + maxItems);
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
