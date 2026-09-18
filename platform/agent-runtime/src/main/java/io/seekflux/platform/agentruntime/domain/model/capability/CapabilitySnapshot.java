package io.seekflux.platform.agentruntime.domain.model.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public record CapabilitySnapshot(
        int schemaVersion,
        String catalogVersion,
        String catalogHash,
        String fingerprint,
        Map<String, SkillDefinition> visibleSkills,
        Map<String, ToolGroupDefinition> toolGroups,
        Set<String> definitionAllowedTools,
        Set<String> activeSkills,
        Set<String> activeToolGroups,
        Set<String> ephemeralSkillIds,
        boolean toolRestrictionEnabled,
        Set<String> requestedTools,
        Set<String> effectiveTools,
        List<String> activeInstructions,
        List<String> lazySkillSummaries) {

    public CapabilitySnapshot {
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("capability snapshot schema version must be positive");
        }
        catalogVersion = requireText(catalogVersion, "capability catalog version");
        catalogHash = requireText(catalogHash, "capability catalog hash");
        fingerprint = requireText(fingerprint, "capability fingerprint");
        visibleSkills = visibleSkills == null ? Map.of() : Map.copyOf(visibleSkills);
        toolGroups = toolGroups == null ? Map.of() : Map.copyOf(toolGroups);
        definitionAllowedTools = copy(definitionAllowedTools);
        activeSkills = copy(activeSkills);
        activeToolGroups = copy(activeToolGroups);
        ephemeralSkillIds = copy(ephemeralSkillIds);
        requestedTools = copy(requestedTools);
        effectiveTools = copy(effectiveTools);
        activeInstructions = activeInstructions == null ? List.of() : List.copyOf(activeInstructions);
        lazySkillSummaries = lazySkillSummaries == null ? List.of() : List.copyOf(lazySkillSummaries);
        if (!visibleSkills.keySet().containsAll(activeSkills)) {
            throw new IllegalArgumentException("active Skills must exist in the frozen snapshot");
        }
        if (!visibleSkills.keySet().containsAll(ephemeralSkillIds)
                || ephemeralSkillIds.stream()
                        .map(visibleSkills::get)
                        .anyMatch(skill -> skill.source() != SkillDefinition.Source.REQUEST)) {
            throw new IllegalArgumentException("ephemeral Skills must be frozen REQUEST definitions");
        }
        if (!toolGroups.keySet().containsAll(activeToolGroups)) {
            throw new IllegalArgumentException("active ToolGroups must exist in the frozen snapshot");
        }
        if (!definitionAllowedTools.containsAll(requestedTools)) {
            throw new IllegalArgumentException("requested Tools cannot exceed AgentDef permissions");
        }
        Set<String> expectedTools = effectiveTools(
                visibleSkills, toolGroups, definitionAllowedTools, activeSkills,
                activeToolGroups, toolRestrictionEnabled, requestedTools);
        if (!effectiveTools.equals(expectedTools)) {
            throw new IllegalArgumentException("effective Tools do not match the frozen capability inputs");
        }
        List<String> expectedInstructions = activeInstructions(visibleSkills, activeSkills);
        if (!activeInstructions.equals(expectedInstructions)) {
            throw new IllegalArgumentException("active instructions do not match the frozen Skills");
        }
        List<String> expectedLazySummaries = lazySkillSummaries(visibleSkills, activeSkills);
        if (!lazySkillSummaries.equals(expectedLazySummaries)) {
            throw new IllegalArgumentException("lazy Skill summaries do not match the frozen Skills");
        }
        if (!"legacy-v1".equals(catalogVersion)) {
            String expectedFingerprint = fingerprint(
                    catalogVersion,
                    catalogHash,
                    visibleSkills,
                    toolGroups,
                    definitionAllowedTools,
                    ephemeralSkillIds,
                    activeSkills,
                    activeToolGroups,
                    toolRestrictionEnabled,
                    requestedTools);
            if (!fingerprint.equals(expectedFingerprint)) {
                throw new IllegalArgumentException("capability snapshot fingerprint does not match its content");
            }
        }
    }

    public static CapabilitySnapshot create(
            CapabilityCatalog catalog,
            Map<String, SkillDefinition> visibleSkills,
            Set<String> definitionAllowedTools,
            Set<String> activeSkills,
            Set<String> activeToolGroups,
            Set<String> ephemeralSkillIds,
            boolean toolRestrictionEnabled,
            Set<String> requestedTools) {
        String fingerprint = fingerprint(
                catalog, visibleSkills, catalog.toolGroups(), definitionAllowedTools, ephemeralSkillIds,
                activeSkills, activeToolGroups, toolRestrictionEnabled, requestedTools);
        return recompute(
                catalog.version(), catalog.contentHash(), fingerprint, visibleSkills,
                catalog.toolGroups(), definitionAllowedTools, activeSkills, activeToolGroups,
                ephemeralSkillIds, toolRestrictionEnabled, requestedTools);
    }

    public static CapabilitySnapshot legacy(Set<String> allowedTools) {
        Set<String> tools = copy(allowedTools);
        return recompute(
                "legacy-v1", sha256("legacy:" + new TreeSet<>(tools)),
                sha256("legacy-snapshot:" + new TreeSet<>(tools)),
                Map.of(), Map.of(), tools, Set.of(), Set.of(), Set.of(), false, Set.of());
    }

    public CapabilitySnapshot switchToolGroups(Set<String> groups) {
        Set<String> normalized = copy(groups);
        if (!toolGroups.keySet().containsAll(normalized)) {
            throw new IllegalArgumentException("ToolGroup switch references an unknown group");
        }
        String updatedFingerprint = fingerprint(
                catalogVersion,
                catalogHash,
                visibleSkills,
                toolGroups,
                definitionAllowedTools,
                ephemeralSkillIds,
                activeSkills,
                normalized,
                toolRestrictionEnabled,
                requestedTools);
        return recompute(
                catalogVersion, catalogHash, updatedFingerprint, visibleSkills, toolGroups,
                definitionAllowedTools, activeSkills, normalized, ephemeralSkillIds,
                toolRestrictionEnabled, requestedTools);
    }

    public Map<String, String> skillVersions() {
        Map<String, String> versions = new TreeMap<>();
        visibleSkills.forEach((id, skill) -> versions.put(id, skill.version()));
        return Map.copyOf(versions);
    }

    public Map<String, String> toolGroupVersions() {
        Map<String, String> versions = new TreeMap<>();
        toolGroups.forEach((id, group) -> versions.put(id, group.version()));
        return Map.copyOf(versions);
    }

    private static CapabilitySnapshot recompute(
            String catalogVersion,
            String catalogHash,
            String fingerprint,
            Map<String, SkillDefinition> visibleSkills,
            Map<String, ToolGroupDefinition> toolGroups,
            Set<String> definitionAllowedTools,
            Set<String> activeSkills,
            Set<String> activeToolGroups,
            Set<String> ephemeralSkillIds,
            boolean toolRestrictionEnabled,
            Set<String> requestedTools) {
        Set<String> effective = effectiveTools(
                visibleSkills, toolGroups, definitionAllowedTools, activeSkills,
                activeToolGroups, toolRestrictionEnabled, requestedTools);
        List<String> instructions = activeInstructions(visibleSkills, activeSkills);
        List<String> lazy = lazySkillSummaries(visibleSkills, activeSkills);
        return new CapabilitySnapshot(
                1, catalogVersion, catalogHash, fingerprint, visibleSkills, toolGroups,
                definitionAllowedTools, activeSkills, activeToolGroups, ephemeralSkillIds,
                toolRestrictionEnabled, requestedTools, effective, instructions, lazy);
    }

    private static String fingerprint(
            CapabilityCatalog catalog,
            Map<String, SkillDefinition> visibleSkills,
            Map<String, ToolGroupDefinition> toolGroups,
            Set<String> definitionAllowedTools,
            Set<String> ephemeralSkillIds,
            Set<String> activeSkills,
            Set<String> activeToolGroups,
            boolean restricted,
            Set<String> requestedTools) {
        return fingerprint(
                catalog.version(), catalog.contentHash(), visibleSkills, toolGroups,
                definitionAllowedTools,
                ephemeralSkillIds, activeSkills, activeToolGroups, restricted, requestedTools);
    }

    private static String fingerprint(
            String catalogVersion,
            String catalogHash,
            Map<String, SkillDefinition> visibleSkills,
            Map<String, ToolGroupDefinition> toolGroups,
            Set<String> definitionAllowedTools,
            Set<String> ephemeralSkillIds,
            Set<String> activeSkills,
            Set<String> activeToolGroups,
            boolean restricted,
            Set<String> requestedTools) {
        String canonical = catalogVersion + '|' + catalogHash + '|'
                + new TreeMap<>(visibleSkills) + '|' + new TreeMap<>(toolGroups) + '|'
                + new TreeSet<>(definitionAllowedTools) + '|'
                + new TreeSet<>(ephemeralSkillIds) + '|' + new TreeSet<>(activeSkills) + '|'
                + new TreeSet<>(activeToolGroups) + '|' + restricted + '|'
                + new TreeSet<>(requestedTools);
        return sha256(canonical);
    }

    private static Set<String> effectiveTools(
            Map<String, SkillDefinition> visibleSkills,
            Map<String, ToolGroupDefinition> toolGroups,
            Set<String> definitionAllowedTools,
            Set<String> activeSkills,
            Set<String> activeToolGroups,
            boolean toolRestrictionEnabled,
            Set<String> requestedTools) {
        Set<String> allowed = new LinkedHashSet<>(definitionAllowedTools);
        for (String skillId : activeSkills) {
            SkillDefinition skill = visibleSkills.get(skillId);
            if (skill == null) {
                throw new IllegalArgumentException("active Skill is unavailable: " + skillId);
            }
            if (!definitionAllowedTools.containsAll(skill.requiredTools())) {
                throw new IllegalArgumentException("Skill requires a Tool outside AgentDef: " + skillId);
            }
            allowed.addAll(skill.requiredTools());
        }

        Map<String, Set<String>> memberships = new LinkedHashMap<>();
        for (ToolGroupDefinition group : toolGroups.values()) {
            for (String tool : group.toolIds()) {
                memberships.computeIfAbsent(tool, ignored -> new LinkedHashSet<>()).add(group.groupId());
            }
        }
        Set<String> effective = new LinkedHashSet<>();
        for (String tool : allowed) {
            Set<String> groups = memberships.get(tool);
            boolean alwaysVisible = groups != null && groups.stream()
                    .map(toolGroups::get)
                    .anyMatch(ToolGroupDefinition::alwaysActive);
            boolean visible = groups == null || groups.isEmpty()
                    || groups.stream().anyMatch(activeToolGroups::contains)
                    || alwaysVisible;
            if (visible && (alwaysVisible || !toolRestrictionEnabled || requestedTools.contains(tool))) {
                effective.add(tool);
            }
        }
        return Set.copyOf(effective);
    }

    private static List<String> activeInstructions(
            Map<String, SkillDefinition> visibleSkills,
            Set<String> activeSkills) {
        List<String> instructions = new ArrayList<>();
        activeSkills.stream().sorted().forEach(id -> {
            SkillDefinition skill = visibleSkills.get(id);
            if (skill != null && skill.type() == SkillDefinition.Type.PROMPT) {
                instructions.add(id + "@" + skill.version() + ":\n" + skill.instruction());
            }
        });
        return List.copyOf(instructions);
    }

    private static List<String> lazySkillSummaries(
            Map<String, SkillDefinition> visibleSkills,
            Set<String> activeSkills) {
        return visibleSkills.values().stream()
                .filter(skill -> skill.type() == SkillDefinition.Type.LAZY)
                .filter(skill -> !activeSkills.contains(skill.skillId()))
                .sorted(java.util.Comparator.comparing(SkillDefinition::skillId))
                .map(skill -> skill.skillId() + "@" + skill.version() + ": " + skill.summary())
                .toList();
    }

    private static Set<String> copy(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
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
}
