package io.seekflux.platform.agentruntime.domain.service.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ContextMessage;
import io.seekflux.platform.agentruntime.infrastructure.prompt.MapPromptResolver;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultContextEngineTest {

    @Test
    void exposesOnlyTheRequestScopedToolSchemaAndSearchPlanAttributes() {
        AgentTool direct = tool("search_direct");
        AgentTool filtered = tool("search_filtered");
        AgentToolRegistry registry = new AgentToolRegistry(List.of(direct, filtered));
        AgentDefinition definition = new AgentDefinition(
                "search-assistant", "v2", "planner-v1", "prompt-v2", "provider-v1",
                Set.of("search_direct", "search_filtered"), 3, 2, Duration.ofSeconds(1), true);
        AgentRunRequest request = new AgentRunRequest(
                "request-1", "session-1", "turn-1", "猫咪护理",
                Map.of(
                        "allowedTools", List.of("search_filtered"),
                        "derivedRequiredTags", List.of("猫咪", "护理")));
        LlmClient llm = new LlmClient() {
            @Override
            public String version() {
                return "provider-v1";
            }

            @Override
            public AgentDecision chat(AssembledContext context) {
                return new AgentDecision.Fallback("unused");
            }
        };
        RuntimeContext runtime = new RuntimeContext(definition, request, llm, Map.of());
        AgentSession session = AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, Instant.EPOCH, "search-assistant", "v2")));
        DefaultContextEngine engine = new DefaultContextEngine(
                new MapPromptResolver(Map.of("prompt-v2", "stable prompt")), registry);

        AssembledContext assembled = engine.assemble(
                session,
                runtime,
                new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()));

        String context = assembled.messages().stream()
                .map(ContextMessage::content)
                .filter(content -> content.contains("runtime_context"))
                .findFirst()
                .orElseThrow();
        assertTrue(context.contains("search_filtered@schema-v1"));
        assertTrue(context.contains("derivedRequiredTags"));
        assertTrue(context.contains("猫咪"));
        assertFalse(context.contains("search_direct@schema-v1"));
    }

    private static AgentTool tool(String name) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "schema-v1",
                        Map.of("query", AgentToolParameter.requiredString(500)));
            }

            @Override
            public AgentToolResult execute(io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext context) {
                return AgentToolResult.success(Map.of(), null);
            }
        };
    }
}
