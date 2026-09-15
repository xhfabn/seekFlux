package io.seekflux.platform.agentruntime.domain.service.context;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
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
    void rebuildsOrderedMultiTurnHistoryFromWorkspaceMessagesWithoutReplayingReasoning() {
        Instant now = Instant.parse("2026-09-13T00:00:00Z");
        AgentMessage.ToolCall call = new AgentMessage.ToolCall(
                "call-1", "search_direct", 0, Map.of("query", "露营"));
        AgentSession session = AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "search-assistant", "v2"),
                new WorkspaceEvent.UserMessage(2, now, "request-1", "turn-1", "杭州露营"),
                new WorkspaceEvent.AssistantMessage(3, now, new AgentMessage.Assistant(
                        1, "assistant-1", "request-1", "turn-1", "run-1", 1,
                        "准备搜索", "private reasoning", false, List.of(call))),
                new WorkspaceEvent.ToolResultMessage(4, now, new AgentMessage.ToolResult(
                        1, "tool-result-1", "request-1", "turn-1", "run-1", 1,
                        "call-1", "search_direct", "schema-v1",
                        AgentMessage.ToolResultStatus.SUCCEEDED,
                        "search_direct:{answer=西湖营地}",
                        Map.of("answer", "西湖营地"),
                        Map.of("title", "西湖营地"),
                        Map.of("answer", "西湖营地"),
                        List.of(), null, "search-trace-1", false, 12)),
                new WorkspaceEvent.RunCompleted(
                        5, now, "run-1", AgentTerminalState.RESULTS_READY, null),
                new WorkspaceEvent.UserMessage(6, now, "request-2", "turn-2", "那亲子适合吗")));
        AgentDefinition definition = new AgentDefinition(
                "search-assistant", "v2", "planner-v1", "prompt-v2", "provider-v1",
                Set.of("search_direct"), 3, 2, Duration.ofSeconds(1), true);
        AgentRunRequest request = new AgentRunRequest(
                "request-2", "session-1", "turn-2", "那亲子适合吗", Map.of());
        RuntimeContext runtime = new RuntimeContext(definition, request, null, Map.of());
        DefaultContextEngine engine = new DefaultContextEngine(
                new MapPromptResolver(Map.of("prompt-v2", "stable prompt")),
                new AgentToolRegistry(List.of(tool("search_direct"))));

        AssembledContext assembled = engine.assemble(
                session,
                runtime,
                new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()));

        List<ContextMessage> history = assembled.messages().stream()
                .filter(message -> message.messageId() != null)
                .toList();
        assertEquals(List.of("user", "assistant", "tool", "user"),
                history.stream().map(ContextMessage::role).toList());
        assertEquals("call-1", history.get(2).toolCallId());
        assertEquals("search_direct", history.get(2).toolName());
        assertTrue(history.get(1).content().contains("tool_calls"));
        assertFalse(assembled.messages().stream()
                .anyMatch(message -> message.content().contains("private reasoning")));
    }

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
            public Effect effect() {
                return Effect.READ_ONLY;
            }

            @Override
            public AgentToolResult execute(io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext context) {
                return AgentToolResult.success(Map.of(), null);
            }
        };
    }
}
