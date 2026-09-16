package io.seekflux.platform.agentruntime.domain.service.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.context.ContextEngine;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.domain.exception.ContextOverflowException;
import io.seekflux.platform.agentruntime.domain.exception.AgentModelOutputException;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.context.ContextAssemblyMode;
import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DefaultAgentLoopContextOverflowTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void retriesOnceWithOverflowFallbackBeforeAnyDecisionOrToolSideEffect() {
        AtomicInteger modelCalls = new AtomicInteger();
        LlmClient llm = llm(context -> {
            modelCalls.incrementAndGet();
            if (context.assemblyMode() == ContextAssemblyMode.NORMAL) {
                throw new ContextOverflowException(413);
            }
            return new AgentDecision.Complete(Map.of("ok", true));
        });
        List<ContextEvent> events = new ArrayList<>();

        AgentRunResult result = loop(events).run(
                session(), runtimeContext(llm), ignored -> -1, new CancellationToken());

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals(2, modelCalls.get());
        assertTrue(events.stream().anyMatch(event ->
                event.type() == ContextEvent.Type.OVERFLOW_RETRY));
    }

    @Test
    void stopsAtTheConfiguredOverflowRetryLimitWithAStableFailure() {
        AtomicInteger modelCalls = new AtomicInteger();
        LlmClient llm = llm(context -> {
            modelCalls.incrementAndGet();
            throw new ContextOverflowException(400);
        });
        List<ContextEvent> events = new ArrayList<>();

        AgentRunResult result = loop(events).run(
                session(), runtimeContext(llm), ignored -> -1, new CancellationToken());

        assertEquals(AgentTerminalState.FAILED, result.state());
        assertEquals(2, modelCalls.get());
        assertEquals("LLM_CONTEXT_OVERFLOW_EXHAUSTED",
                result.trace().steps().getFirst().errorCode());
        assertTrue(events.stream().anyMatch(event ->
                event.type() == ContextEvent.Type.OVERFLOW_EXHAUSTED));
    }

    @Test
    void mapsOutputGuardExhaustionToItsStableRuntimeFailureCode() {
        LlmClient llm = llm(context -> {
            throw new AgentModelOutputException(
                    "LLM_OUTPUT_GUARD_EXHAUSTED", new IllegalStateException("invalid"));
        });

        AgentRunResult result = loop(new ArrayList<>()).run(
                session(), runtimeContext(llm), ignored -> -1, new CancellationToken());

        assertEquals(AgentTerminalState.FAILED, result.state());
        assertEquals("LLM_OUTPUT_GUARD_EXHAUSTED",
                result.trace().steps().getFirst().errorCode());
    }

    private DefaultAgentLoop loop(List<ContextEvent> events) {
        AgentToolRegistry tools = new AgentToolRegistry(List.of(tool()));
        AgentRuntime runtime = new AgentRuntime(
                tools,
                new DefaultAgentToolExecutor(tools),
                executor,
                ignored -> { },
                Clock.systemUTC());
        ContextEngine contexts = new ContextEngine() {
            @Override
            public AssembledContext assemble(
                    AgentSession session,
                    RuntimeContext runtimeContext,
                    AgentDecisionContext decisionContext) {
                return assemble(session, runtimeContext, decisionContext, ContextAssemblyMode.NORMAL);
            }

            @Override
            public AssembledContext assemble(
                    AgentSession session,
                    RuntimeContext runtimeContext,
                    AgentDecisionContext decisionContext,
                    ContextAssemblyMode mode) {
                return new AssembledContext(
                        decisionContext, List.of(), "test", 500, 300,
                        mode == ContextAssemblyMode.OVERFLOW_FALLBACK,
                        0,
                        mode);
            }

            @Override
            public int overflowRetryLimit() {
                return 1;
            }
        };
        return new DefaultAgentLoop(runtime, contexts, Clock.systemUTC(), events::add);
    }

    private static RuntimeContext runtimeContext(LlmClient llm) {
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop", "prompt", llm.version(),
                Set.of("unused"), 2, 1, Duration.ofSeconds(2), false);
        AgentRunRequest request = new AgentRunRequest(
                "request", "session", "turn", "query", Map.of());
        return new RuntimeContext(definition, request, llm, Map.of());
    }

    private static AgentSession session() {
        return AgentSession.replay("session", List.of(
                new WorkspaceEvent.SessionCreated(1, Instant.EPOCH, "agent", "v1"),
                new WorkspaceEvent.UserMessage(2, Instant.EPOCH, "request", "turn", "query")));
    }

    private static LlmClient llm(java.util.function.Function<AssembledContext, AgentDecision> action) {
        return new LlmClient() {
            @Override
            public String version() {
                return "test";
            }

            @Override
            public AgentDecision chat(AssembledContext context) {
                return action.apply(context);
            }
        };
    }

    private static AgentTool tool() {
        return new AgentTool() {
            @Override
            public String name() {
                return "unused";
            }

            @Override
            public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "v1", Map.of("query", AgentToolParameter.requiredString(10)));
            }

            @Override
            public Effect effect() {
                return Effect.READ_ONLY;
            }

            @Override
            public AgentToolResult execute(AgentToolContext context) {
                throw new AssertionError("overflow retry must not execute tools");
            }
        };
    }
}
