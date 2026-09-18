package io.seekflux.platform.agentruntime.domain.model.capability;

import java.util.Objects;
import java.util.Set;

public record SkillDefinition(
        int schemaVersion,
        String skillId,
        String version,
        Source source,
        Type type,
        String instruction,
        String summary,
        Set<String> requiredTools,
        Set<String> toolGroups,
        boolean autoActivate) {

    public static final int MAX_INSTRUCTION_LENGTH = 16_384;
    public static final int MAX_SUMMARY_LENGTH = 512;

    public SkillDefinition {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("Skill schema version must be positive");
        }
        skillId = requireText(skillId, "skill id", 128);
        version = requireText(version, "skill version", 128);
        source = Objects.requireNonNull(source, "skill source must not be null");
        type = Objects.requireNonNull(type, "skill type must not be null");
        instruction = requireText(instruction, "skill instruction", MAX_INSTRUCTION_LENGTH);
        summary = requireText(summary, "skill summary", MAX_SUMMARY_LENGTH);
        requiredTools = normalized(requiredTools, "required Tool");
        toolGroups = normalized(toolGroups, "ToolGroup");
    }

    public SkillDefinition(
            String skillId,
            String version,
            Type type,
            String instruction,
            String summary,
            Set<String> requiredTools,
            Set<String> toolGroups,
            boolean autoActivate) {
        this(1, skillId, version, Source.GLOBAL, type, instruction, summary,
                requiredTools, toolGroups, autoActivate);
    }

    public static SkillDefinition ephemeral(
            String skillId,
            String version,
            Type type,
            String instruction,
            String summary,
            Set<String> requiredTools,
            Set<String> toolGroups,
            boolean autoActivate) {
        return new SkillDefinition(
                1, skillId, version, Source.REQUEST, type, instruction, summary,
                requiredTools, toolGroups, autoActivate);
    }

    public enum Source {
        GLOBAL,
        REQUEST
    }

    public enum Type {
        PROMPT,
        LAZY
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
            result.add(requireText(value, name, 128));
        }
        return Set.copyOf(result);
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
