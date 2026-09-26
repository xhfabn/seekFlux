package io.seekflux.apps.agentserver.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import io.seekflux.agent.infrastructure.tool.SearchDirectTool;
import io.seekflux.agent.infrastructure.tool.SearchFilteredTool;
import io.seekflux.platform.agentruntime.application.command.CapabilityRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import io.seekflux.platform.agentruntime.infrastructure.tool.SwitchToolGroupsTool;
import io.seekflux.search.port.in.SearchUseCase;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AgentRuntimeCapabilityConfigurationTest {

    @Test
    void realSearchAgentCatalogLoadsTwoBusinessGroupsAndKeepsControlToolVisible() {
        AgentRuntimeConfiguration configuration = new AgentRuntimeConfiguration();
        var catalog = configuration.agentCapabilityCatalog();
        SearchUseCase unusedSearch = query -> {
            throw new AssertionError("configuration validation must not execute Search");
        };
        SearchDirectTool direct = configuration.searchDirectTool(unusedSearch);
        SearchFilteredTool filtered = configuration.searchFilteredTool(unusedSearch);
        SwitchToolGroupsTool switcher = configuration.switchToolGroupsTool(catalog);
        var registry = configuration.agentToolRegistry(
                configuration.seekFluxAgentTools(direct, filtered, switcher),
                configuration.agentToolRegistrationPolicy());
        catalog.validateAvailableTools(registry.names());
        LlmClient decisionClient = new LlmClient() {
            @Override
            public String version() {
                return "test-provider-v1";
            }

            @Override
            public AgentDecision chat(
                    io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext context) {
                return new AgentDecision.Complete(Map.of());
            }
        };
        var definitions = configuration.seekFluxAgentDefinitions(
                Map.of(
                        "search-assistant", decisionClient,
                        "search-precise", decisionClient),
                new AgentMcpProperties(false, java.util.List.of()),
                2_500);

        var snapshot = new CapabilityResolver(catalog).resolve(
                definitions.get("search-assistant"),
                CapabilityActivationState.EMPTY,
                CapabilityRequest.restrictTools(Set.of(SearchDirectTool.NAME)));

        assertThat(catalog.toolGroups().keySet())
                .contains("search-broad-tools", "search-precise-tools", "capability-control");
        assertThat(snapshot.effectiveTools())
                .containsExactlyInAnyOrder(SearchDirectTool.NAME, SwitchToolGroupsTool.NAME);
        assertThat(snapshot.effectiveTools()).doesNotContain(SearchFilteredTool.NAME);
        assertThat(snapshot.skillVersions())
                .containsEntry("search-broad", "search-broad-v1")
                .containsEntry("search-precise", "search-precise-v1");
    }
}
