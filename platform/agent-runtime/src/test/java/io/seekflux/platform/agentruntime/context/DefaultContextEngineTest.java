package io.seekflux.platform.agentruntime.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.AgentDecisionContext;
import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.AgentTool;
import io.seekflux.platform.agentruntime.AgentToolParameter;
import io.seekflux.platform.agentruntime.AgentToolRegistry;
import io.seekflux.platform.agentruntime.AgentToolResult;
import io.seekflux.platform.agentruntime.AgentToolSchema;
import io.seekflux.platform.agentruntime.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import io.seekflux.platform.agentruntime.session.AgentSession;
import io.seekflux.platform.agentruntime.session.WorkspaceEvent;
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
            public AgentToolResult execute(io.seekflux.platform.agentruntime.AgentToolContext context) {
                return AgentToolResult.success(Map.of(), null);
            }
        };
    }
}
