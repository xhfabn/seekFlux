package io.seekflux.platform.agentruntime.domain.service.capability;

import io.seekflux.platform.agentruntime.application.command.CapabilityRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.event.CapabilityEventRecorder;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityCatalog;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityEvent;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilitySnapshot;
import io.seekflux.platform.agentruntime.domain.model.capability.SkillDefinition;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class CapabilityResolver {

    private final CapabilityCatalog catalog;
    private final CapabilityEventRecorder events;
    private final Clock clock;

    public CapabilityResolver(CapabilityCatalog catalog) {
        this(catalog, CapabilityEventRecorder.NOOP, Clock.systemUTC());
    }

    public CapabilityResolver(
            CapabilityCatalog catalog,
            CapabilityEventRecorder events,
            Clock clock) {
        this.catalog = catalog == null ? CapabilityCatalog.EMPTY : catalog;
        this.events = events == null ? CapabilityEventRecorder.NOOP : events;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public static CapabilityResolver legacy() {
        return new CapabilityResolver(CapabilityCatalog.EMPTY);
    }

    public CapabilitySnapshot resolve(
            AgentDefinition definition,
            CapabilityActivationState state,
            CapabilityRequest request) {
        try {
            CapabilitySnapshot snapshot = resolveValidated(definition, state, request);
            record(
                    CapabilityEvent.Type.RESOLVED,
                    definition.id(),
                    CapabilityEvent.Outcome.ACCEPTED,
                    CapabilityEvent.Reason.EXECUTION_START,
                    snapshot);
            return snapshot;
        } catch (IllegalArgumentException invalid) {
            record(
                    CapabilityEvent.Type.RESOLVED,
                    definition.id(),
                    CapabilityEvent.Outcome.REJECTED,
                    CapabilityEvent.Reason.VALIDATION,
                    null);
            throw invalid;
        }
    }

    private CapabilitySnapshot resolveValidated(
            AgentDefinition definition,
            CapabilityActivationState state,
            CapabilityRequest request) {
        CapabilityActivationState activation = state == null
                ? CapabilityActivationState.EMPTY : state;
        activation = materialize(activation, definition);
        CapabilityRequest selection = request == null ? CapabilityRequest.NONE : request;
        validateCatalogFor(definition);

        Map<String, SkillDefinition> visible = new LinkedHashMap<>();
        for (String skillId : definition.skillRefs()) {
            SkillDefinition skill = catalog.skills().get(skillId);
            if (skill == null) {
                throw new IllegalArgumentException("AgentDef references an unknown Skill: " + skillId);
            }
            visible.put(skillId, skill);
        }
        Set<String> ephemeralIds = new LinkedHashSet<>();
        for (SkillDefinition ephemeral : selection.ephemeralSkills()) {
            if (ephemeral.source() != SkillDefinition.Source.REQUEST) {
                throw new IllegalArgumentException(
                        "ephemeral Skill must declare REQUEST source: " + ephemeral.skillId());
            }
            if (!definition.skillRefs().contains(ephemeral.skillId())) {
                throw new IllegalArgumentException(
                        "ephemeral Skill is outside AgentDef references: " + ephemeral.skillId());
            }
            if (!definition.allowedTools().containsAll(ephemeral.requiredTools())) {
                throw new IllegalArgumentException(
                        "ephemeral Skill requires a Tool outside AgentDef: " + ephemeral.skillId());
            }
            if (!catalog.toolGroups().keySet().containsAll(ephemeral.toolGroups())) {
                throw new IllegalArgumentException(
                        "ephemeral Skill references an unknown ToolGroup: " + ephemeral.skillId());
            }
            visible.put(ephemeral.skillId(), ephemeral);
            ephemeralIds.add(ephemeral.skillId());
        }

        Set<String> activeSkills = new LinkedHashSet<>(activation.activeSkills());
        if (activation.version() == 0) {
            visible.values().stream().filter(SkillDefinition::autoActivate)
                    .map(SkillDefinition::skillId).forEach(activeSkills::add);
        }
        activeSkills.addAll(ephemeralIds);
        if (!visible.keySet().containsAll(activeSkills)) {
            throw new IllegalArgumentException("Session activates a Skill outside AgentDef references");
        }

        Set<String> activeGroups = new LinkedHashSet<>();
        activeGroups.addAll(activation.activeToolGroups());
        for (String skillId : activeSkills) {
            activeGroups.addAll(visible.get(skillId).toolGroups());
        }
        activeGroups.removeAll(selection.deactivateToolGroups());
        activeGroups.addAll(selection.activateToolGroups());
        if (!catalog.toolGroups().keySet().containsAll(activeGroups)) {
            throw new IllegalArgumentException("capability request activates an unknown ToolGroup");
        }
        if (selection.toolRestrictionEnabled()
                && !definition.allowedTools().containsAll(selection.requestedTools())) {
            throw new IllegalArgumentException("request contains a Tool outside AgentDef permissions");
        }
        return CapabilitySnapshot.create(
                catalog,
                visible,
                definition.allowedTools(),
                activeSkills,
                activeGroups,
                ephemeralIds,
                selection.toolRestrictionEnabled(),
                selection.requestedTools());
    }

    public void recordToolGroupSwitch(String agentId, CapabilitySnapshot snapshot) {
        record(
                CapabilityEvent.Type.TOOL_GROUPS_SWITCHED,
                agentId,
                CapabilityEvent.Outcome.ACCEPTED,
                CapabilityEvent.Reason.TOOL_RESULT,
                snapshot);
    }

    public CapabilityActivationState materialize(CapabilityActivationState state) {
        return materialize(state, null);
    }

    public CapabilityActivationState materialize(
            CapabilityActivationState state,
            AgentDefinition definition) {
        CapabilityActivationState current = state == null
                ? CapabilityActivationState.EMPTY : state;
        if (current.version() == 0) {
            Set<String> initialSkills = new LinkedHashSet<>(current.activeSkills());
            if (definition != null) {
                definition.skillRefs().stream()
                        .map(catalog.skills()::get)
                        .filter(java.util.Objects::nonNull)
                        .filter(SkillDefinition::autoActivate)
                        .map(SkillDefinition::skillId)
                        .forEach(initialSkills::add);
            }
            Set<String> initialGroups = new LinkedHashSet<>(current.activeToolGroups());
            initialGroups.addAll(catalog.initialActiveGroups());
            return new CapabilityActivationState(
                    0, initialSkills, initialGroups);
        }
        return current;
    }

    public void validateRestorable(CapabilitySnapshot snapshot) {
        if (snapshot == null || "legacy-v1".equals(snapshot.catalogVersion())) {
            return;
        }
        if (!catalog.version().equals(snapshot.catalogVersion())
                || !catalog.contentHash().equals(snapshot.catalogHash())) {
            throw new IllegalStateException("CAPABILITY_SNAPSHOT_UNAVAILABLE");
        }
    }

    private void validateCatalogFor(AgentDefinition definition) {
        for (String skillId : definition.skillRefs()) {
            SkillDefinition skill = catalog.skills().get(skillId);
            if (skill != null && !definition.allowedTools().containsAll(skill.requiredTools())) {
                throw new IllegalArgumentException(
                        "Skill requires a Tool outside AgentDef permissions: " + skillId);
            }
        }
    }

    private void record(
            CapabilityEvent.Type type,
            String agentId,
            CapabilityEvent.Outcome outcome,
            CapabilityEvent.Reason reason,
            CapabilitySnapshot snapshot) {
        try {
            events.record(new CapabilityEvent(
                    type,
                    agentId,
                    snapshot == null ? catalog.version() : snapshot.catalogVersion(),
                    outcome,
                    reason,
                    snapshot == null ? 0 : snapshot.activeSkills().size(),
                    snapshot == null ? 0 : snapshot.activeToolGroups().size(),
                    snapshot == null ? 0 : snapshot.effectiveTools().size(),
                    clock.instant()));
        } catch (RuntimeException ignored) {
            // Metrics and diagnostics cannot alter capability resolution semantics.
        }
    }
}
