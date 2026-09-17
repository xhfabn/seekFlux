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
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextCompactionStore;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import io.seekflux.platform.agentruntime.domain.model.context.CompactionSummary;
import io.seekflux.platform.agentruntime.domain.model.context.ContextAssemblyMode;
import io.seekflux.platform.agentruntime.domain.model.context.ContextCompactionMode;
import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;
import io.seekflux.platform.agentruntime.domain.model.context.ContextWindowPolicy;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
        AssembledContext withoutSchemas = new DefaultContextEngine(
                new MapPromptResolver(Map.of("prompt-v2", "stable prompt")))
                .assemble(
                        session,
                        runtime,
                        new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()));

        String context = assembled.messages().stream()
                .map(ContextMessage::content)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertTrue(context.contains("search_filtered@schema-v1"));
        assertTrue(context.contains("derivedRequiredTags"));
        assertTrue(context.contains("猫咪"));
        assertFalse(context.contains("search_direct@schema-v1"));
        assertTrue(assembled.estimatedTokens() > withoutSchemas.estimatedTokens());
        assertEquals(List.of("search_filtered"),
                assembled.tools().stream().map(tool -> tool.name()).toList());
        assertEquals("object", assembled.tools().getFirst().inputSchema().get("type"));
        assertTrue(withoutSchemas.tools().isEmpty());
    }

    @Test
    void compactsCompleteTurnsWithoutBreakingToolPairsAndPersistsAnInclusiveCutoff() {
        AgentSession session = longSession(4, true);
        AgentRunRequest request = new AgentRunRequest(
                "request-4", "session-1", "turn-4", "当前问题", Map.of());
        RuntimeContext runtime = runtime(request);
        MemoryCompactions compactions = new MemoryCompactions();
        List<ContextEvent> events = new ArrayList<>();
        ContextWindowPolicy policy = new ContextWindowPolicy(
                10_000, 360, 220, 2, ContextCompactionMode.SYNC,
                Duration.ofMillis(200), 1);
        DefaultContextEngine engine = new DefaultContextEngine(
                new MapPromptResolver(Map.of("prompt-v2", "stable prompt")),
                new AgentToolRegistry(List.of(tool("search_direct"))),
                compactions,
                events::add,
                policy,
                Runnable::run,
                Clock.systemUTC());

        AssembledContext normal = engine.assemble(
                session,
                runtime,
                new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()));

        assertTrue(normal.compacted());
        assertTrue(normal.compactionCutoff() > 0);
        assertTrue(normal.messages().stream().anyMatch(message ->
                message.content().contains("compaction_summary")));
        assertTrue(compactions.latest("session-1").orElseThrow().summary().contains("search_direct"));
        List<ContextMessage> remainingHistory = normal.messages().stream()
                .filter(message -> message.messageId() != null)
                .toList();
        assertEquals(List.of("user", "assistant", "tool", "user", "assistant", "tool"),
                remainingHistory.stream().map(ContextMessage::role).toList());
        assertEquals("call-4", remainingHistory.get(5).toolCallId());
        assertTrue(events.stream().anyMatch(event ->
                event.type() == ContextEvent.Type.COMPACTION_COMMITTED));
        assertTrue(normal.estimatedTokens() < 420);

        AssembledContext overflow = engine.assemble(
                session,
                runtime,
                new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()),
                ContextAssemblyMode.OVERFLOW_FALLBACK);
        assertEquals(ContextAssemblyMode.OVERFLOW_FALLBACK, overflow.assemblyMode());
        assertTrue(overflow.compactionCutoff() >= normal.compactionCutoff());
        assertTrue(compactions.summaries.size() <= 2);
        assertTrue(compactions.noGap);
        assertTrue(events.stream().anyMatch(event ->
                event.type() == ContextEvent.Type.SKELETON_FALLBACK));
    }

    @Test
    void asyncCompactionUsesSingleFlightAndLeavesTheCurrentAssemblyUsable() {
        AgentSession session = longSession(4, true);
        AgentRunRequest request = new AgentRunRequest(
                "request-4", "session-1", "turn-4", "当前问题", Map.of());
        MemoryCompactions compactions = new MemoryCompactions();
        List<Runnable> scheduled = new ArrayList<>();
        List<ContextEvent> events = new ArrayList<>();
        ContextWindowPolicy policy = new ContextWindowPolicy(
                10_000, 360, 220, 2, ContextCompactionMode.ASYNC,
                Duration.ofMillis(200), 1);
        DefaultContextEngine engine = new DefaultContextEngine(
                new MapPromptResolver(Map.of("prompt-v2", "stable prompt")),
                new AgentToolRegistry(List.of(tool("search_direct"))),
                compactions,
                events::add,
                policy,
                scheduled::add,
                Clock.systemUTC());

        AssembledContext first = engine.assemble(
                session, runtime(request),
                new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()));
        AssembledContext second = engine.assemble(
                session, runtime(request),
                new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()));

        assertFalse(first.compacted());
        assertFalse(second.compacted());
        assertEquals(1, scheduled.size());
        assertTrue(events.stream().anyMatch(event ->
                event.type() == ContextEvent.Type.COMPACTION_ASYNC_SKIPPED));
        scheduled.getFirst().run();
        assertTrue(compactions.latest("session-1").isPresent());
    }

    @Test
    void reportsCompactionNoopAndExhaustionWhenOnlyTheProtectedCurrentTurnRemains() {
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        AgentSession session = AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(1, now, "search-assistant", "v2"),
                new WorkspaceEvent.UserMessage(
                        2, now, 1, "user-current", "request-current", "turn-current",
                        "必须保留的当前问题".repeat(80))));
        AgentRunRequest request = new AgentRunRequest(
                "request-current", "session-1", "turn-current", "当前问题", Map.of());
        List<ContextEvent> events = new ArrayList<>();
        DefaultContextEngine engine = new DefaultContextEngine(
                new MapPromptResolver(Map.of("prompt-v2", "stable prompt")),
                new AgentToolRegistry(List.of(tool("search_direct"))),
                new MemoryCompactions(),
                events::add,
                new ContextWindowPolicy(
                        10_000, 128, 64, 1, ContextCompactionMode.SYNC,
                        Duration.ofMillis(200), 1),
                Runnable::run,
                Clock.systemUTC());

        AssembledContext assembled = engine.assemble(
                session, runtime(request),
                new AgentDecisionContext(request, 1, Duration.ofSeconds(1), List.of()));

        assertTrue(assembled.messages().stream()
                .anyMatch(message -> "user-current".equals(message.messageId())));
        assertTrue(events.stream().anyMatch(event ->
                event.type() == ContextEvent.Type.COMPACTION_NOOP));
        assertTrue(events.stream().anyMatch(event ->
                event.type() == ContextEvent.Type.COMPACTION_EXHAUSTED));
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

    private static RuntimeContext runtime(AgentRunRequest request) {
        AgentDefinition definition = new AgentDefinition(
                "search-assistant", "v2", "planner-v1", "prompt-v2", "provider-v1",
                Set.of("search_direct"), 5, 4, Duration.ofSeconds(2), true);
        return new RuntimeContext(definition, request, null, Map.of());
    }

    private static AgentSession longSession(int turns, boolean longOldTurns) {
        Instant now = Instant.parse("2026-09-16T00:00:00Z");
        List<WorkspaceEvent> events = new ArrayList<>();
        events.add(new WorkspaceEvent.SessionCreated(1, now, "search-assistant", "v2"));
        long position = 2;
        for (int turn = 1; turn <= turns; turn++) {
            String suffix = longOldTurns && turn <= turns - 2
                    ? "亲子约束".repeat(70) : "当前问题";
            String requestId = "request-" + turn;
            String turnId = "turn-" + turn;
            String callId = "call-" + turn;
            events.add(new WorkspaceEvent.UserMessage(
                    position++, now, 1, "user-" + turn,
                    requestId, turnId, suffix));
            events.add(new WorkspaceEvent.AssistantMessage(
                    position++, now, new AgentMessage.Assistant(
                            1, "assistant-" + turn, requestId, turnId, "run-" + turn, turn,
                            "调用搜索", null, false,
                            List.of(new AgentMessage.ToolCall(
                                    callId, "search_direct", 0, Map.of("query", suffix))))));
            events.add(new WorkspaceEvent.ToolResultMessage(
                    position++, now, new AgentMessage.ToolResult(
                            1, "result-" + turn, requestId, turnId, "run-" + turn, turn,
                            callId, "search_direct", "schema-v1",
                            AgentMessage.ToolResultStatus.SUCCEEDED,
                            "search_direct:{constraint=亲子,turn=" + turn + "}",
                            Map.of(), Map.of(), Map.of("constraint", "亲子"),
                            List.of(), null, null, false, 1)));
        }
        return AgentSession.replay("session-1", events);
    }

    private static final class MemoryCompactions implements ContextCompactionStore {
        private final List<CompactionSummary> summaries = new ArrayList<>();
        private boolean noGap = true;

        @Override
        public Optional<CompactionSummary> latest(String sessionId) {
            return summaries.stream()
                    .filter(summary -> summary.sessionId().equals(sessionId))
                    .max(java.util.Comparator.comparingLong(CompactionSummary::inclusiveCutoff));
        }

        @Override
        public synchronized CompactionSummary append(CompactionSummary summary) {
            Optional<CompactionSummary> current = latest(summary.sessionId());
            long expected = current.map(CompactionSummary::inclusiveCutoff).orElse(0L);
            if (expected != summary.fromExclusive()) {
                noGap = false;
                throw new IllegalStateException("gap");
            }
            summaries.add(summary);
            return summary;
        }
    }
}
