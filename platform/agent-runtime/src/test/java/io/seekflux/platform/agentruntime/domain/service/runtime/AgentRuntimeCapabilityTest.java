package io.seekflux.platform.agentruntime.domain.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.ToolExecutionPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.ToolExecutionObserver;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityCatalog;
import io.seekflux.platform.agentruntime.domain.model.capability.ToolGroupDefinition;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import io.seekflux.platform.agentruntime.domain.service.execution.AgentCallGuard;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import io.seekflux.platform.agentruntime.infrastructure.tool.SwitchToolGroupsTool;
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

class AgentRuntimeCapabilityTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    @Test
    void switchesToolGroupsOnlyForTheNextModelTurnAndFreezesTheNewSnapshot() {
        AgentTool broad = tool("search_direct", context -> AgentToolResult.success(Map.of(), null));
        AgentTool precise = tool("search_filtered", context -> AgentToolResult.success(Map.of(), null));
        CapabilityCatalog catalog = CapabilityCatalog.of(
                "catalog-v1",
                List.of(),
                List.of(
                        new ToolGroupDefinition(
                                "control", "control-v1", "control",
                                Set.of(SwitchToolGroupsTool.NAME), true),
                        new ToolGroupDefinition(
                                "broad", "broad-v1", "broad", Set.of("search_direct"), false),
                        new ToolGroupDefinition(
                                "precise", "precise-v1", "precise", Set.of("search_filtered"), false)),
                Set.of("control", "broad"));
        AgentToolRegistry tools = new AgentToolRegistry(
                List.of(new SwitchToolGroupsTool(catalog), broad, precise));
        CapabilityResolver resolver = new CapabilityResolver(catalog);
        AgentDefinition definition = new AgentDefinition(
                "agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                Set.of(SwitchToolGroupsTool.NAME, "search_direct", "search_filtered"), Set.of(),
                3, 2, Duration.ofSeconds(1), true);
        var initial = resolver.resolve(
                definition, CapabilityActivationState.EMPTY,
                io.seekflux.platform.agentruntime.application.command.CapabilityRequest.NONE);
        List<PushEvent> push = new ArrayList<>();
        AgentRuntime runtime = new AgentRuntime(
                tools,
                new DefaultAgentToolExecutor(tools),
                executor,
                AgentRunRecorder.NOOP,
                Clock.systemUTC(),
                AgentCallGuard.UNBOUNDED,
                ToolExecutionPolicy.ALLOW_ALL,
                ToolExecutionObserver.NOOP,
                resolver);

        var result = runtime.run(
                definition,
                new AgentRunRequest("request", "session", "turn", "input", Map.of()),
                context -> {
                    if (context.step() == 1) {
                        assertTrue(context.capabilities().effectiveTools().contains("search_direct"));
                        assertFalse(context.capabilities().effectiveTools().contains("search_filtered"));
                        return new AgentDecision.CallTool(
                                SwitchToolGroupsTool.NAME,
                                Map.of("active_groups", List.of("precise")));
                    }
                    assertFalse(context.capabilities().effectiveTools().contains("search_direct"));
                    assertTrue(context.capabilities().effectiveTools().contains("search_filtered"));
                    return new AgentDecision.Complete(Map.of("switched", true));
                },
                new CancellationToken(),
                AgentRecoveryExecution.DISABLED,
                event -> {
                    push.add(event);
                    return push.size();
                },
                initial);

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals(Set.of("precise"),
                result.trace().definition().capabilities().activeToolGroups());
        assertEquals(Set.of(SwitchToolGroupsTool.NAME, "search_filtered"),
                result.trace().definition().capabilities().effectiveTools());
        assertFalse(initial.fingerprint().equals(
                result.trace().definition().capabilities().fingerprint()));
        assertTrue(push.stream().anyMatch(PushEvent.CapabilitiesChanged.class::isInstance));
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
                return new AgentToolSchema(name + "-v1", Map.of());
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
