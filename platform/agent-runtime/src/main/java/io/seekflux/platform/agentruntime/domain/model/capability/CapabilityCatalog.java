package io.seekflux.platform.agentruntime.domain.model.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public record CapabilityCatalog(
        String version,
        String contentHash,
        Map<String, SkillDefinition> skills,
        Map<String, ToolGroupDefinition> toolGroups,
        Set<String> initialActiveGroups) {

    public static final CapabilityCatalog EMPTY = of("capability-catalog-empty-v1", ListHolder.EMPTY_SKILLS,
            ListHolder.EMPTY_GROUPS, Set.of());

    public CapabilityCatalog {
        version = requireText(version, "capability catalog version");
        contentHash = requireText(contentHash, "capability catalog content hash");
        skills = skills == null ? Map.of() : Map.copyOf(skills);
        toolGroups = toolGroups == null ? Map.of() : Map.copyOf(toolGroups);
        initialActiveGroups = initialActiveGroups == null ? Set.of() : Set.copyOf(initialActiveGroups);
        if (!toolGroups.keySet().containsAll(initialActiveGroups)) {
            throw new IllegalArgumentException("initial ToolGroups must exist in the catalog");
        }
    }

    public static CapabilityCatalog of(
            String version,
            Collection<SkillDefinition> skills,
            Collection<ToolGroupDefinition> toolGroups,
            Set<String> initialActiveGroups) {
        Map<String, SkillDefinition> skillIndex = indexSkills(skills);
        Map<String, ToolGroupDefinition> groupIndex = indexGroups(toolGroups);
        validateReferences(skillIndex, groupIndex);
        Set<String> initial = initialActiveGroups == null ? Set.of() : Set.copyOf(initialActiveGroups);
        String canonical = new TreeMap<>(skillIndex) + "|" + new TreeMap<>(groupIndex) + "|"
                + new java.util.TreeSet<>(initial);
        return new CapabilityCatalog(version, sha256(canonical), skillIndex, groupIndex, initial);
    }

    public void validateAvailableTools(Set<String> registeredTools) {
        Set<String> available = registeredTools == null ? Set.of() : Set.copyOf(registeredTools);
        for (SkillDefinition skill : skills.values()) {
            if (!available.containsAll(skill.requiredTools())) {
                throw new IllegalArgumentException(
                        "Skill references an unavailable Tool: " + skill.skillId());
            }
        }
        for (ToolGroupDefinition group : toolGroups.values()) {
            if (!available.containsAll(group.toolIds())) {
                throw new IllegalArgumentException(
                        "ToolGroup references an unavailable Tool: " + group.groupId());
            }
        }
    }

    private static Map<String, SkillDefinition> indexSkills(Collection<SkillDefinition> values) {
        Map<String, SkillDefinition> indexed = new LinkedHashMap<>();
        if (values != null) {
            for (SkillDefinition value : values) {
                if (value.source() != SkillDefinition.Source.GLOBAL) {
                    throw new IllegalArgumentException(
                            "the global catalog cannot contain a request Skill: " + value.skillId());
                }
                SkillDefinition previous = indexed.put(value.skillId(), value);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate Skill id: " + value.skillId());
                }
            }
        }
        return Map.copyOf(indexed);
    }

    private static Map<String, ToolGroupDefinition> indexGroups(Collection<ToolGroupDefinition> values) {
        Map<String, ToolGroupDefinition> indexed = new LinkedHashMap<>();
        if (values != null) {
            for (ToolGroupDefinition value : values) {
                ToolGroupDefinition previous = indexed.put(value.groupId(), value);
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate ToolGroup id: " + value.groupId());
                }
            }
        }
        return Map.copyOf(indexed);
    }

    private static void validateReferences(
            Map<String, SkillDefinition> skills,
            Map<String, ToolGroupDefinition> groups) {
        for (SkillDefinition skill : skills.values()) {
            if (!groups.keySet().containsAll(skill.toolGroups())) {
                throw new IllegalArgumentException(
                        "Skill references an unknown ToolGroup: " + skill.skillId());
            }
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static final class ListHolder {
        private static final java.util.List<SkillDefinition> EMPTY_SKILLS = java.util.List.of();
        private static final java.util.List<ToolGroupDefinition> EMPTY_GROUPS = java.util.List.of();
    }
}
