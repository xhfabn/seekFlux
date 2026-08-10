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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import io.seekflux.platform.agentruntime.llm.LlmUsage;
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

    @Test
    void executesToolFanOutInParallelUnderOneDeadline() {
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        java.util.function.Function<AgentToolContext, AgentToolResult> action = context -> {
            int running = active.incrementAndGet();
            maxActive.accumulateAndGet(running, Math::max);
            try {
                barrier.await(500, TimeUnit.MILLISECONDS);
                return AgentToolResult.success(Map.of("tool", context.arguments().get("query")), null);
            } catch (Exception error) {
                return AgentToolResult.failure("BARRIER_FAILED");
            } finally {
                active.decrementAndGet();
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(
                tool("search_direct", action),
                tool("search_filtered", action)));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC());

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 3, Set.of("search_direct", "search_filtered"), 2),
                request(),
                context -> context.observations().isEmpty()
                        ? new AgentDecision.CallTools(List.of(
                                new AgentDecision.ToolCall("search_direct", Map.of("query", "宽搜")),
                                new AgentDecision.ToolCall("search_filtered", Map.of("query", "精搜"))))
                        : new AgentDecision.Complete(Map.of("observations", context.observations().size())));

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals(2, result.output().get("observations"));
        assertEquals(2, maxActive.get());
        assertEquals(2, result.trace().steps().stream()
                .filter(step -> "CALL_TOOL".equals(step.action()))
                .count());
    }

    @Test
    void repairsToolArgumentsOnceBeforeInvocation() {
        AgentTool tool = tool(context -> {
            assertEquals(1L, context.arguments().get("page"));
            assertTrue(!context.arguments().containsKey("unknown"));
            return AgentToolResult.success(Map.of("repaired", true), null);
        });
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC());

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 3),
                request(),
                context -> context.observations().isEmpty()
                        ? new AgentDecision.CallTool("search_direct", Map.of(
                                "query", "露营", "page", "1", "unknown", true))
                        : new AgentDecision.Complete(context.observations().getFirst().result().output()));

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals("SUCCEEDED_REPAIRED", result.trace().steps().getFirst().status());
    }

    @Test
    void stopsRepeatedInvocationAsNoProgress() {
        AtomicInteger invocations = new AtomicInteger();
        AgentTool tool = tool(context -> {
            invocations.incrementAndGet();
            return AgentToolResult.success(Map.of("same", true), null);
        });
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC());

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 3, Set.of("search_direct"), 2),
                request(),
                ignored -> new AgentDecision.CallTool("search_direct", Map.of("query", "露营")));

        assertEquals(AgentTerminalState.FALLBACK_REQUIRED, result.state());
        assertEquals("NO_PROGRESS_DETECTED", result.fallbackReason());
        assertEquals(1, invocations.get());
    }

    @Test
    void modelFaultInjectionProducesDeterministicFallback() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool(
                context -> AgentToolResult.success(Map.of(), null))));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC(),
                new AgentCallGuard(1, 1, type -> {
                    if (type == AgentCallGuard.CallType.MODEL) {
                        throw new AgentCallGuard.CallRejectedException("INJECTED_MODEL_FAILURE");
                    }
                }));

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 2), request(),
                ignored -> new AgentDecision.Complete(Map.of()));

        assertEquals(AgentTerminalState.FALLBACK_REQUIRED, result.state());
        assertEquals("INJECTED_MODEL_FAILURE", result.fallbackReason());
    }

    @Test
    void aggregatesMeasuredModelUsageIntoTrace() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool(
                context -> AgentToolResult.success(Map.of(), null))));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC());

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 2), request(), context -> {
                    context.recordUsage(new LlmUsage(100, 25, 125, 175, true));
                    return new AgentDecision.Complete(Map.of());
                });

        assertEquals(125, result.trace().llmUsage().totalTokens());
        assertEquals(175, result.trace().llmUsage().costMicros());
        assertTrue(result.trace().llmUsage().measured());
    }

    @Test
    void toolFaultInjectionReturnsPartialFailureAsStableFallback() {
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool(
                context -> AgentToolResult.success(Map.of(), null))));
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC(),
                new AgentCallGuard(1, 1, type -> {
                    if (type == AgentCallGuard.CallType.TOOL) {
                        throw new AgentCallGuard.CallRejectedException("INJECTED_TOOL_FAILURE");
                    }
                }));

        AgentRunResult result = runtime.run(
                definition(Duration.ofSeconds(1), 2), request(),
                ignored -> new AgentDecision.CallTool("search_direct", Map.of("query", "露营")));

        assertEquals(AgentTerminalState.FALLBACK_REQUIRED, result.state());
        assertEquals("INJECTED_TOOL_FAILURE", result.fallbackReason());
    }

    private static AgentDefinition definition(Duration timeout, int maxSteps) {
        return definition(timeout, maxSteps, Set.of("search_direct"), 1);
    }

    private static AgentDefinition definition(
            Duration timeout,
            int maxSteps,
            Set<String> allowedTools,
            int maxToolCalls) {
        return new AgentDefinition(
                "search-assistant",
                "v1",
                "loop-v1",
                "prompt-v1",
                "decision-v1",
                allowedTools,
                maxSteps,
                maxToolCalls,
                timeout,
                true);
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest("request-1", "session-1", "turn-1", "露营", Map.of());
    }

    private static AgentTool tool(java.util.function.Function<AgentToolContext, AgentToolResult> action) {
        return tool("search_direct", action);
    }

    private static AgentTool tool(
            String name,
            java.util.function.Function<AgentToolContext, AgentToolResult> action) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "search-direct-tool-test-v1",
                        Map.of(
                                "query", AgentToolParameter.requiredString(500),
                                "page", AgentToolParameter.optionalInteger(0, 199)));
            }

            @Override
            public AgentToolResult execute(AgentToolContext context) {
                return action.apply(context);
            }
        };
    }
}
