package io.seekflux.platform.agentruntime.domain.service.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.command.CapabilityRequest;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityCatalog;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilitySnapshot;
import io.seekflux.platform.agentruntime.domain.model.capability.SkillDefinition;
import io.seekflux.platform.agentruntime.domain.model.capability.ToolGroupDefinition;
import io.seekflux.platform.agentruntime.infrastructure.tool.SwitchToolGroupsTool;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class CapabilityResolverTest {

    @Test
    void resolvesSessionActivationAndRequestToolRestrictionWithinAgentBoundary() {
        CapabilityResolver resolver = new CapabilityResolver(catalog());
        CapabilityActivationState state = new CapabilityActivationState(
                2, Set.of("search"), Set.of("precise"));

        var snapshot = resolver.resolve(
                definition(),
                state,
                CapabilityRequest.restrictTools(Set.of("search_filtered")));

        assertEquals(Set.of("search"), snapshot.activeSkills());
        assertEquals(Set.of("precise"), snapshot.activeToolGroups());
        assertEquals(Set.of("search_filtered"), snapshot.effectiveTools());
        assertTrue(snapshot.activeInstructions().getFirst().contains("global instruction"));
        assertEquals("catalog-v1", snapshot.catalogVersion());
    }

    @Test
    void ephemeralSkillShadowsTheGlobalDefinitionWithoutLeakingItsRequiredTool() {
        CapabilityResolver resolver = new CapabilityResolver(catalog());
        SkillDefinition ephemeral = SkillDefinition.ephemeral(
                "search", "ephemeral-v1", SkillDefinition.Type.PROMPT,
                "ephemeral instruction", "ephemeral", Set.of("search_direct"),
                Set.of("broad"), true);
        CapabilityRequest request = new CapabilityRequest(
                1, List.of(ephemeral), Set.of(), Set.of(), false, Set.of());

        var snapshot = resolver.resolve(
                definition(), CapabilityActivationState.EMPTY, request);

        assertEquals("ephemeral-v1", snapshot.visibleSkills().get("search").version());
        assertEquals(Set.of("search"), snapshot.ephemeralSkillIds());
        assertTrue(snapshot.activeInstructions().getFirst().contains("ephemeral instruction"));
        assertFalse(snapshot.activeInstructions().getFirst().contains("global instruction"));
        assertEquals(Set.of("search_direct"), snapshot.effectiveTools());
    }

    @Test
    void rejectsEphemeralSkillThatAttemptsToExpandAgentToolPermissions() {
        CapabilityResolver resolver = new CapabilityResolver(catalog());
        SkillDefinition invalid = SkillDefinition.ephemeral(
                "search", "ephemeral-v1", SkillDefinition.Type.PROMPT,
                "invalid", "invalid", Set.of("publish"), Set.of(), true);

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                definition(),
                CapabilityActivationState.EMPTY,
                new CapabilityRequest(1, List.of(invalid), Set.of(), Set.of(), false, Set.of())));
    }

    @Test
    void failsRecoveryWhenTheFrozenCatalogVersionIsUnavailable() {
        CapabilityResolver first = new CapabilityResolver(catalog());
        var snapshot = first.resolve(
                definition(), CapabilityActivationState.EMPTY, CapabilityRequest.NONE);
        CapabilityCatalog replacement = CapabilityCatalog.of(
                "catalog-v2", List.of(), List.of(), Set.of());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> new CapabilityResolver(replacement).validateRestorable(snapshot));

        assertEquals("CAPABILITY_SNAPSHOT_UNAVAILABLE", error.getMessage());
    }

    @Test
    void alwaysActiveControlToolSurvivesARequestLevelBusinessToolRestriction() {
        CapabilityCatalog catalog = CapabilityCatalog.of(
                "catalog-v1",
                List.of(),
                List.of(
                        new ToolGroupDefinition(
                                "control", "control-v1", "control",
                                Set.of(SwitchToolGroupsTool.NAME), true),
                        new ToolGroupDefinition(
                                "broad", "broad-v1", "broad",
                                Set.of("search_direct"), false)),
                Set.of("broad"));
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                Set.of(SwitchToolGroupsTool.NAME, "search_direct"), Set.of(),
                3, 2, Duration.ofSeconds(1), true);

        var snapshot = new CapabilityResolver(catalog).resolve(
                definition,
                CapabilityActivationState.EMPTY,
                CapabilityRequest.restrictTools(Set.of("search_direct")));

        assertEquals(Set.of(SwitchToolGroupsTool.NAME, "search_direct"),
                snapshot.effectiveTools());
    }

    @Test
    void catalogValidationRejectsAGroupWhoseToolIsNotRegistered() {
        CapabilityCatalog catalog = CapabilityCatalog.of(
                "catalog-v1",
                List.of(),
                List.of(new ToolGroupDefinition(
                        "missing", "missing-v1", "missing", Set.of("missing_tool"), false)),
                Set.of());

        assertThrows(IllegalArgumentException.class,
                () -> catalog.validateAvailableTools(Set.of("search_direct")));
    }

    @Test
    void firstPersistentChangeMaterializesAutoActivatedSkillsBeforeVersionAdvances() {
        CapabilityResolver resolver = new CapabilityResolver(catalog());

        CapabilityActivationState initial = resolver.materialize(
                CapabilityActivationState.EMPTY, definition());
        CapabilityActivationState persisted = new io.seekflux.platform.agentruntime.domain.model.capability.CapabilityChange(
                1, "operation-1", 0, Set.of(), Set.of(), Set.of(), Set.of("broad"))
                .apply(initial);

        assertEquals(Set.of("search"), persisted.activeSkills());
        assertTrue(resolver.resolve(definition(), persisted, CapabilityRequest.NONE)
                .activeSkills().contains("search"));
    }

    @Test
    void rejectsAFrozenSnapshotWhoseDerivedToolsOrGroupDefinitionsWereAltered() {
        CapabilitySnapshot snapshot = new CapabilityResolver(catalog()).resolve(
                definition(),
                new CapabilityActivationState(2, Set.of("search"), Set.of("precise")),
                CapabilityRequest.NONE);

        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot(
                snapshot.schemaVersion(), snapshot.catalogVersion(), snapshot.catalogHash(),
                snapshot.fingerprint(), snapshot.visibleSkills(), snapshot.toolGroups(),
                snapshot.definitionAllowedTools(), snapshot.activeSkills(),
                snapshot.activeToolGroups(), snapshot.ephemeralSkillIds(),
                snapshot.toolRestrictionEnabled(), snapshot.requestedTools(),
                Set.of("search_direct", "search_filtered"), snapshot.activeInstructions(),
                snapshot.lazySkillSummaries()));

        Map<String, ToolGroupDefinition> alteredGroups = new java.util.LinkedHashMap<>(
                snapshot.toolGroups());
        alteredGroups.put("broad", new ToolGroupDefinition(
                "broad", "broad-v1", "altered description",
                Set.of("search_direct"), false));
        assertThrows(IllegalArgumentException.class, () -> new CapabilitySnapshot(
                snapshot.schemaVersion(), snapshot.catalogVersion(), snapshot.catalogHash(),
                snapshot.fingerprint(), snapshot.visibleSkills(), alteredGroups,
                snapshot.definitionAllowedTools(), snapshot.activeSkills(),
                snapshot.activeToolGroups(), snapshot.ephemeralSkillIds(),
                snapshot.toolRestrictionEnabled(), snapshot.requestedTools(),
                snapshot.effectiveTools(), snapshot.activeInstructions(),
                snapshot.lazySkillSummaries()));
    }

    @Test
    void keepsSchemaV1SnapshotsRestorableWhileNewSnapshotsFreezeRegistryMembership() throws Exception {
        CapabilitySnapshot current = new CapabilityResolver(catalog()).resolve(
                definition(), CapabilityActivationState.EMPTY, CapabilityRequest.NONE);
        String canonical = current.catalogVersion() + '|' + current.catalogHash() + '|'
                + new TreeMap<>(current.visibleSkills()) + '|'
                + new TreeMap<>(current.toolGroups()) + '|'
                + new TreeSet<>(current.definitionAllowedTools()) + '|'
                + new TreeSet<>(current.ephemeralSkillIds()) + '|'
                + new TreeSet<>(current.activeSkills()) + '|'
                + new TreeSet<>(current.activeToolGroups()) + '|'
                + current.toolRestrictionEnabled() + '|'
                + new TreeSet<>(current.requestedTools());
        String v1Fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.getBytes(StandardCharsets.UTF_8)));

        CapabilitySnapshot restored = new CapabilitySnapshot(
                1, current.catalogVersion(), current.catalogHash(), v1Fingerprint,
                current.visibleSkills(), current.toolGroups(), current.definitionAllowedTools(),
                current.activeSkills(), current.activeToolGroups(), current.ephemeralSkillIds(),
                current.toolRestrictionEnabled(), current.requestedTools(), current.effectiveTools(),
                current.activeInstructions(), current.lazySkillSummaries());

        assertEquals(2, current.schemaVersion());
        assertEquals(current.definitionAllowedTools(), restored.registeredTools());
        assertEquals(current.effectiveTools(), restored.effectiveTools());
    }

    @Test
    void freezesOnlyToolsRegisteredAtExecutionStart() {
        CapabilityResolver resolver = new CapabilityResolver(
                catalog(),
                io.seekflux.platform.agentruntime.application.spi.capability.event
                        .CapabilityEventRecorder.NOOP,
                java.time.Clock.systemUTC(),
                () -> Set.of("search_direct"));

        CapabilitySnapshot snapshot = resolver.resolve(
                definition(), CapabilityActivationState.EMPTY, CapabilityRequest.NONE);

        assertEquals(Set.of("search_direct"), snapshot.registeredTools());
        assertEquals(Set.of("search_direct"), snapshot.effectiveTools());
    }

    private static CapabilityCatalog catalog() {
        return CapabilityCatalog.of(
                "catalog-v1",
                List.of(new SkillDefinition(
                        "search", "search-v1", SkillDefinition.Type.PROMPT,
                        "global instruction", "search", Set.of("search_filtered"),
                        Set.of("precise"), true)),
                List.of(
                        new ToolGroupDefinition(
                                "broad", "broad-v1", "broad", Set.of("search_direct"), false),
                        new ToolGroupDefinition(
                                "precise", "precise-v1", "precise", Set.of("search_filtered"), false)),
                Set.of("broad"));
    }

    private static AgentDefinition definition() {
        return new AgentDefinition(
                "agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                Set.of("search_direct", "search_filtered"), Set.of("search"),
                3, 2, Duration.ofSeconds(1), true);
    }
}
