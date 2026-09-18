package io.seekflux.apps.agentserver.interfaces.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityCatalog;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityChange;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityUpdateResult;
import io.seekflux.platform.agentruntime.domain.model.capability.ToolGroupDefinition;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AgentCapabilityControllerTest {

    @Test
    void validatesAndPersistsAnIdempotentVersionedToolGroupChange() {
        Instant now = Instant.parse("2026-09-17T00:00:00Z");
        AgentSession session = AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "agent", "v1")));
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                Set.of("search_direct", "search_filtered"), Set.of(),
                3, 2, Duration.ofSeconds(1), true);
        CapabilityCatalog catalog = CapabilityCatalog.of(
                "catalog-v1", List.of(), List.of(
                        new ToolGroupDefinition(
                                "broad", "broad-v1", "broad", Set.of("search_direct"), false),
                        new ToolGroupDefinition(
                                "precise", "precise-v1", "precise", Set.of("search_filtered"), false)),
                Set.of("broad", "precise"));
        AtomicReference<CapabilityActivationState> captured = new AtomicReference<>();
        AgentSessionStore store = sessions(session, captured);
        AgentCapabilityController controller = new AgentCapabilityController(
                store,
                Map.of("agent", definition),
                new CapabilityResolver(catalog),
                Clock.fixed(now, ZoneOffset.UTC));

        CapabilityActivationState result = controller.update(
                "user-1",
                "session-1",
                new AgentCapabilityController.CapabilityUpdateRequest(
                        "operation-1", 0L, Set.of(), Set.of(), Set.of(), Set.of("precise")));

        assertEquals(1, result.version());
        assertEquals(Set.of("broad"), result.activeToolGroups());
        assertEquals(result, captured.get());
    }

    private static AgentSessionStore sessions(
            AgentSession session, AtomicReference<CapabilityActivationState> captured) {
        return new AgentSessionStore() {
            @Override public Optional<AgentSession> restoreFresh(String sessionId) {
                return Optional.of(session);
            }

            @Override public AgentSession createIfAbsent(
                    String sessionId, AgentDefinition definition, Instant eventTime) {
                return session;
            }

            @Override public IngressCommitResult commitIngress(
                    AgentRunRequest request, long fencingToken, Instant eventTime) {
                return IngressCommitResult.COMMITTED;
            }

            @Override public void appendOutcome(
                    String sessionId, AgentRunResult result, long fencingToken, Instant eventTime) {
            }

            @Override public CapabilityUpdateResult updateCapabilities(
                    String sessionId,
                    CapabilityChange change,
                    CapabilityActivationState updatedState,
                    String actor,
                    Instant eventTime) {
                captured.set(updatedState);
                return new CapabilityUpdateResult(CapabilityUpdateResult.Status.UPDATED, updatedState);
            }
        };
    }
}
