package io.seekflux.platform.agenteval.runtime;

import io.seekflux.platform.agenteval.*;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import java.util.*;
import java.util.function.Supplier;
import static io.seekflux.platform.agenteval.Evaluation.*;

/** Generates reusable Runtime contract cases from one valid sample; business assertions remain explicit. */
public final class ToolContractSuite {
    private ToolContractSuite() {}
    public static Suite create(String toolName, Map<String, Object> validArguments, Map<String, Object> expectedOutput) {
        List<Case> cases = new ArrayList<>();
        Map<String, Object> parameters = Map.of("allowedTools", List.of(toolName), "plan",
                List.of(Map.of("action", "tool", "tool", toolName, "arguments", validArguments)));
        cases.add(new Case("tool-success", "run the tool", Set.of("basic"), parameters, List.of(),
                List.of(eq("state", "/state", "RESULTS_READY"), eq("output", "/output", expectedOutput)), 8000));
        for (Phase phase : List.of(Phase.BEFORE_TOOL, Phase.AFTER_TOOL)) {
            cases.add(new Case("tool-failure-" + phase.name().toLowerCase(Locale.ROOT), "run the tool",
                    Set.of("basic", "tool-faults"), parameters,
                    List.of(new Fault("injected", phase, toolName, 1, FaultAction.THROW, 0)),
                    List.of(eq("state", "/state", "FALLBACK_REQUIRED"), eq("reason", "/fallbackReason", "TOOL_EXECUTION_FAILED")), 8000));
        }
        cases.add(new Case("tool-cancellation", "run the tool", Set.of("basic", "cancellation"), parameters,
                List.of(new Fault("cancel", Phase.BEFORE_TOOL, toolName, 1, FaultAction.CANCEL, 0)),
                List.of(eq("state", "/state", "CANCELLED")), 8000));
        return new Suite("tool-contract-" + toolName, "1.0", Map.of("contract", "safe-tool-v1"), cases);
    }
    /** Factory must supply a fresh isolated tool per case; MUTATING tools require a durable target. */
    public static RuntimeEvaluationTarget target(String id, String version, Supplier<? extends AgentTool> factory) {
        return new RuntimeEvaluationTarget(id, version, c -> {
            var tool = factory.get();
            if (tool.effect() == AgentTool.Effect.MUTATING)
                throw new IllegalArgumentException("MUTATING tool contracts require a durable target and effect probe");
            var script = new ScriptedFixture(c);
            return new RuntimeFixture() {
                public Collection<? extends AgentTool> tools() { return List.of(tool); }
                public AgentPlanner planner() { return script.planner(); }
                public AgentDefinition definition() { return script.definition(); }
            };
        });
    }
    private static Check eq(String id, String path, Object expected) { return new Check(id, path, Operator.EQUALS, expected); }
}

