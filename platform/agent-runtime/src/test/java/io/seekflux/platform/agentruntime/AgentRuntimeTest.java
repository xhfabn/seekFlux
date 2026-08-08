package io.seekflux.platform.agentruntime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void completesFiniteToolLoopAndFreezesVersionsInTrace() {
        AgentTool tool = tool(context -> AgentToolResult.success(
                Map.of("answer", "ok"), "retrieval-trace-1"));
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        List<AgentRunEvent> events = new ArrayList<>();
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                events::add,
                Clock.systemUTC());

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 3),
                request(),
                context -> context.observations().isEmpty()
                        ? new AgentDecision.CallTool("search_direct", Map.of("query", "露营"))
                        : new AgentDecision.Complete(context.observations().getLast().result().output()));

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals("ok", result.output().get("answer"));
        assertEquals("search-direct-tool-test-v1",
                result.trace().definition().toolSchemaVersions().get("search_direct"));
        assertEquals("retrieval-trace-1", result.trace().steps().getFirst().linkedTraceId());
        assertEquals(5, events.size());
        assertEquals(AgentRunEvent.Type.RUN_STARTED, events.getFirst().type());
        assertEquals(AgentRunEvent.Type.RUN_COMPLETED, events.getLast().type());
    }

    @Test
    void rejectsInvalidToolArgumentsBeforeInvocation() {
        int[] invocations = {0};
        AgentTool tool = tool(context -> {
            invocations[0]++;
            return AgentToolResult.success(Map.of(), null);
        });
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC());

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 2),
                request(),
                ignored -> new AgentDecision.CallTool("search_direct", Map.of("unknown", true)));

        assertEquals(AgentTerminalState.FALLBACK_REQUIRED, result.state());
        assertEquals("TOOL_ARGUMENT_INVALID", result.fallbackReason());
        assertEquals(0, invocations[0]);
    }

    @Test
    void cancelsTimedOutStepAndReturnsStableFallback() {
        AgentTool tool = tool(context -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return AgentToolResult.success(Map.of("late", true), null);
        });
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC());

        AgentRunResult result = runtime.run(
                definition(Duration.ofMillis(30), 2),
                request(),
                ignored -> new AgentDecision.CallTool("search_direct", Map.of("query", "露营")));

        assertEquals(AgentTerminalState.FALLBACK_REQUIRED, result.state());
        assertEquals("AGENT_DEADLINE_EXCEEDED", result.fallbackReason());
        assertTrue(result.degraded());
    }

    @Test
    void returnsClarificationWithoutCallingTool() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool(
                context -> AgentToolResult.success(Map.of(), null))));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC());

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 2),
                request(),
                ignored -> new AgentDecision.Clarify("请补充主题"));

        assertEquals(AgentTerminalState.NEED_CLARIFICATION, result.state());
        assertEquals("请补充主题", result.clarification());
        assertNotNull(result.trace());
    }

    private static AgentDefinition definition(Duration timeout, int maxSteps) {
        return new AgentDefinition(
                "search-assistant",
                "v1",
                "loop-v1",
                "prompt-v1",
                "decision-v1",
                Set.of("search_direct"),
                maxSteps,
                1,
                timeout,
                true);
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest("request-1", "session-1", "turn-1", "露营", Map.of());
    }

    private static AgentTool tool(java.util.function.Function<AgentToolContext, AgentToolResult> action) {
        return new AgentTool() {
            @Override
            public String name() {
                return "search_direct";
            }

            @Override
            public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "search-direct-tool-test-v1",
                        Map.of("query", AgentToolParameter.requiredString(500)));
            }

            @Override
            public AgentToolResult execute(AgentToolContext context) {
                return action.apply(context);
            }
        };
    }
}
