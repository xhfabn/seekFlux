package io.seekflux.platform.agentruntime.domain.service.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.context.ContextEngine;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.WorkspaceEvent;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.service.runtime.AgentRuntime;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DefaultAgentLoopCancellationTest {

    private final ExecutorService agentExecutor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        agentExecutor.shutdownNow();
    }

    @Test
    void cancellationBeforeLoopDoesNotCallModelOrBecomeFallback() {
        AtomicInteger modelCalls = new AtomicInteger();
        LlmClient llm = llm(context -> {
            modelCalls.incrementAndGet();
            return new AgentDecision.Complete(Map.of());
        });
        CancellationToken token = new CancellationToken();
        token.cancel(CancellationCause.USER_CANCEL);

        AgentRunResult result = loop(List.of()).run(
                session(), runtimeContext(llm), event -> -1, token);

        assertEquals(AgentTerminalState.CANCELLED, result.state());
        assertEquals("USER_CANCEL", result.cancellationReason());
        assertEquals("USER_CANCEL", result.trace().cancellationReason());
        assertNull(result.fallbackReason());
        assertFalse(result.degraded());
        assertEquals(0, modelCalls.get());
    }

    @Test
    void cancellationInterruptsInFlightModelAndPublishesCancelledOutcome() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        LlmClient llm = llm(context -> {
            entered.countDown();
            try {
                Thread.sleep(Duration.ofSeconds(10));
                return new AgentDecision.Complete(Map.of("late", true));
            } catch (InterruptedException cancelled) {
                interrupted.set(true);
                stopped.countDown();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted", cancelled);
            }
        });
        CancellationToken token = new CancellationToken();
        List<PushEvent> events = new ArrayList<>();

        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                loop(List.of()).run(session(), runtimeContext(llm), event -> {
                    events.add(event);
                    return events.size() - 1L;
                }, token));
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        token.cancel(CancellationCause.USER_CANCEL);
        AgentRunResult result = running.get(1, TimeUnit.SECONDS);

        assertEquals(AgentTerminalState.CANCELLED, result.state());
        assertEquals("USER_CANCEL", result.cancellationReason());
        assertNull(result.fallbackReason());
        assertEquals("CANCELLED", result.trace().steps().getFirst().status());
        assertEquals("USER_CANCEL", result.trace().steps().getFirst().errorCode());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
        PushEvent.LoopCompleted completed = (PushEvent.LoopCompleted) events.getLast();
        assertEquals(AgentTerminalState.CANCELLED, completed.state());
        assertEquals("USER_CANCEL", completed.cancellationReason());
    }

    @Test
    void cancellationInterruptsInFlightToolAndPreventsAnotherModelTurn() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean toolSawCancellation = new AtomicBoolean();
        AgentTool tool = tool(context -> {
            entered.countDown();
            try {
                while (true) {
                    context.cancellationToken().throwIfCancelled();
                    Thread.sleep(10);
                }
            } catch (AgentCancellationException cancelled) {
                toolSawCancellation.set(true);
                return AgentToolResult.success(Map.of("late", true), null);
            } catch (InterruptedException cancelled) {
                toolSawCancellation.set(context.cancellationToken().isCancelled());
                Thread.currentThread().interrupt();
                return AgentToolResult.success(Map.of("late", true), null);
            } finally {
                stopped.countDown();
            }
        });
        AtomicInteger modelCalls = new AtomicInteger();
        LlmClient llm = llm(context -> {
            modelCalls.incrementAndGet();
            return new AgentDecision.CallTool("search_direct", Map.of("query", "露营"));
        });
        CancellationToken token = new CancellationToken();

        CompletableFuture<AgentRunResult> running = CompletableFuture.supplyAsync(() ->
                loop(List.of(tool)).run(session(), runtimeContext(llm), event -> -1, token));
        assertTrue(entered.await(1, TimeUnit.SECONDS));

        token.cancel(CancellationCause.STEER);
        AgentRunResult result = running.get(1, TimeUnit.SECONDS);

        assertEquals(AgentTerminalState.CANCELLED, result.state());
        assertEquals("STEER", result.cancellationReason());
        assertNull(result.fallbackReason());
        assertEquals(1, modelCalls.get());
        assertEquals("CALL_TOOL", result.trace().steps().getLast().action());
        assertEquals("CANCELLED", result.trace().steps().getLast().status());
        assertEquals("STEER", result.trace().steps().getLast().errorCode());
        assertEquals(2, result.messages().size());
        assertTrue(result.messages().getFirst() instanceof AgentMessage.Assistant);
        AgentMessage.ToolResult cancelled = (AgentMessage.ToolResult) result.messages().getLast();
        assertEquals(AgentMessage.ToolResultStatus.CANCELLED, cancelled.status());
        assertEquals("STEER", cancelled.errorCode());
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        assertTrue(toolSawCancellation.get());
    }

    private DefaultAgentLoop loop(List<AgentTool> tools) {
        List<AgentTool> registered = tools.isEmpty()
                ? List.of(tool(context -> AgentToolResult.success(Map.of(), null)))
                : tools;
        AgentToolRegistry registry = new AgentToolRegistry(registered);
        AgentRuntime runtime = new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                agentExecutor,
                ignored -> { },
                Clock.systemUTC());
        ContextEngine contextEngine = (session, runtimeContext, decisionContext) ->
                new AssembledContext(decisionContext, List.of(), "test", 0);
        return new DefaultAgentLoop(runtime, contextEngine, Clock.systemUTC());
    }

    private static RuntimeContext runtimeContext(LlmClient llm) {
        AgentDefinition definition = new AgentDefinition(
                "search-assistant",
                "v1",
                "loop-v1",
                "prompt-v1",
                llm.version(),
                Set.of("search_direct"),
                3,
                2,
                Duration.ofSeconds(5),
                true);
        AgentRunRequest request = new AgentRunRequest(
                "request-1", "session-1", "turn-1", "露营", Map.of());
        return new RuntimeContext(definition, request, llm, Map.of());
    }

    private static AgentSession session() {
        return AgentSession.replay("session-1", List.of(
                new WorkspaceEvent.SessionCreated(
                        1, Instant.parse("2026-09-13T00:00:00Z"), "search-assistant", "v1")));
    }

    private static LlmClient llm(java.util.function.Function<AssembledContext, AgentDecision> action) {
        return new LlmClient() {
            @Override
            public String version() {
                return "test-llm-v1";
            }

            @Override
            public AgentDecision chat(AssembledContext context) {
                return action.apply(context);
            }
        };
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
