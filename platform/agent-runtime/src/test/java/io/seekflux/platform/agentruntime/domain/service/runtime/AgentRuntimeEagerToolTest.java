package io.seekflux.platform.agentruntime.domain.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.EagerToolDispatcher;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentRuntimeEagerToolTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void startsValidatedReadOnlyToolBeforePlannerReturnsAndReusesItsResult() {
        CountDownLatch toolStarted = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        AgentTool tool = tool(context -> {
            invocations.incrementAndGet();
            toolStarted.countDown();
            return AgentToolResult.success(Map.of("answer", context.arguments().get("query")), null);
        });
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        AgentRuntime runtime = runtime(registry);
        List<PushEvent> events = new CopyOnWriteArrayList<>();

        var result = runtime.run(
                definition(), request(), context -> {
                    if (!context.observations().isEmpty()) {
                        return new AgentDecision.Complete(context.observations().getFirst().result().output());
                    }
                    EagerToolDispatcher.Dispatch dispatch = context.dispatchEagerTool(
                            0, "search_direct", Map.of("query", "露营"));
                    assertEquals(EagerToolDispatcher.Disposition.STARTED, dispatch.disposition());
                    try {
                        assertTrue(toolStarted.await(500, TimeUnit.MILLISECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                    return new AgentDecision.CallTool("search_direct", Map.of("query", "露营"));
                },
                new CancellationToken(),
                AgentRecoveryExecution.DISABLED,
                event -> {
                    events.add(event);
                    return events.size() - 1L;
                });

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals("露营", result.output().get("answer"));
        assertEquals(1, invocations.get());
        assertTrue(events.stream().anyMatch(event ->
                event instanceof PushEvent.ToolStarted started && started.eager()));
        assertTrue(events.stream().anyMatch(PushEvent.CheckpointSaved.class::isInstance));
    }

    @Test
    void doesNotStartEagerToolWhenSchemaValidationFails() {
        AtomicInteger invocations = new AtomicInteger();
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool(context -> {
            invocations.incrementAndGet();
            return AgentToolResult.success(Map.of(), null);
        })));
        AgentRuntime runtime = runtime(registry);

        var result = runtime.run(definition(), request(), context -> {
            EagerToolDispatcher.Dispatch dispatch = context.dispatchEagerTool(
                    0, "search_direct", Map.of("unknown", true));
            assertEquals(EagerToolDispatcher.Disposition.INELIGIBLE, dispatch.disposition());
            return new AgentDecision.Fallback("STOP");
        });

        assertEquals(AgentTerminalState.FALLBACK_REQUIRED, result.state());
        assertEquals(0, invocations.get());
    }

    private AgentRuntime runtime(AgentToolRegistry registry) {
        return new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC());
    }

    private static AgentDefinition definition() {
        return new AgentDefinition(
                "search-assistant", "v1", "loop-v1", "prompt-v1", "decision-v1",
                Set.of("search_direct"), 3, 1, Duration.ofSeconds(2), true);
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest(
                "request-eager", "session-eager", "turn-eager", "露营", Map.of());
    }

    private static AgentTool tool(
            java.util.function.Function<AgentToolContext, AgentToolResult> action) {
        return new AgentTool() {
            @Override
            public String name() {
                return "search_direct";
            }

            @Override
            public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "search-direct-v1",
                        Map.of("query", AgentToolParameter.requiredString(100)));
            }

            @Override
            public Effect effect() {
                return Effect.READ_ONLY;
            }

            @Override
            public AgentToolResult execute(AgentToolContext context) {
                return action.apply(context);
            }
        };
    }
}
