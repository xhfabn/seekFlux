package io.seekflux.platform.agenteval.runtime;

import io.seekflux.platform.agenteval.Evaluation;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.application.spi.business.tool.*;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.tool.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Generic deterministic fixture: a record lookup and idempotent key/value writes. */
public final class ScriptedFixture implements RuntimeFixture {
    private final Map<String, Object> config;
    private final Map<String, Object> values = new ConcurrentHashMap<>(Map.of("alpha", "ready", "beta", "pending"));
    private final Map<String, AtomicInteger> executions = new ConcurrentHashMap<>();
    private final AtomicInteger writes = new AtomicInteger();
    public ScriptedFixture(Evaluation.Case c) { config = c.parameters(); }
    public Collection<? extends AgentTool> tools() {
        return List.of(tool("lookup_record", false), tool("set_value", true));
    }
    private AgentTool tool(String name, boolean write) {
        executions.putIfAbsent(name, new AtomicInteger());
        return new AgentTool() {
            public String name() { return name; }
            public String description() { return write ? "Set an isolated fixture value." : "Read an isolated fixture record."; }
            public Effect effect() { return write ? Effect.IDEMPOTENT : Effect.READ_ONLY; }
            public AgentToolSchema schema() {
                return new AgentToolSchema("fixture-v1", write
                        ? Map.of("key", AgentToolParameter.requiredString(80), "value", AgentToolParameter.requiredString(100))
                        : Map.of("key", AgentToolParameter.requiredString(80), "limit", AgentToolParameter.optionalInteger(1, 10)));
            }
            public AgentToolResult execute(AgentToolContext context) {
                executions.get(name).incrementAndGet();
                String key = (String) context.arguments().get("key");
                if (write) {
                    values.put(key, context.arguments().get("value")); writes.incrementAndGet();
                }
                return AgentToolResult.success(Map.of("key", key, "value", values.getOrDefault(key, "missing")), null);
            }
        };
    }
    @SuppressWarnings("unchecked")
    public AgentPlanner planner() {
        List<Map<String, Object>> script = (List<Map<String, Object>>) config.getOrDefault("plan", List.of());
        AtomicInteger index = new AtomicInteger();
        return context -> {
            int next = index.getAndIncrement();
            if (next >= script.size()) {
                if (context.observations().isEmpty()) return new AgentDecision.Complete(Map.of("answer", "done"));
                var last = context.observations().getLast().result();
                return last.success() ? new AgentDecision.Complete(last.output()) : new AgentDecision.Fallback(last.errorCode());
            }
            Map<String, Object> action = script.get(next);
            String type = (String) action.get("action");
            return switch (type) {
                case "tool" -> new AgentDecision.CallTool((String) action.get("tool"), (Map<String, Object>) action.get("arguments"));
                case "parallel" -> new AgentDecision.CallTools(((List<Map<String, Object>>) action.get("calls")).stream()
                        .map(call -> new AgentDecision.ToolCall((String) call.get("tool"), (Map<String, Object>) call.get("arguments"))).toList());
                case "complete" -> new AgentDecision.Complete((Map<String, Object>) action.get("output"));
                case "clarify" -> new AgentDecision.Clarify((String) action.get("question"));
                case "fallback" -> new AgentDecision.Fallback((String) action.get("reason"));
                default -> throw new IllegalArgumentException("unknown script action: " + type);
            };
        };
    }
    @SuppressWarnings("unchecked")
    public AgentDefinition definition() {
        Set<String> allowed = new HashSet<>((List<String>) config.getOrDefault("allowedTools", List.of("lookup_record", "set_value")));
        return new AgentDefinition("generic-eval-agent", "1", "script-v1", "fixture-v1", "script-v1", allowed,
                number("maxSteps", 6), number("maxToolCalls", 4), Duration.ofMillis(number("runtimeTimeoutMillis", 2000)), true);
    }
    public ToolExecutionPolicy policy() {
        return context -> switch ((String) config.getOrDefault("policy", "allow")) {
            case "deny" -> ToolExecutionPolicy.Decision.deny("fixture policy");
            case "modify" -> {
                Map<String, Object> arguments = new HashMap<>(context.arguments()); arguments.put("key", "beta");
                yield ToolExecutionPolicy.Decision.modify(arguments, "fixture redirects key");
            }
            case "allow" -> ToolExecutionPolicy.Decision.allow(context.arguments());
            default -> throw new IllegalArgumentException("unknown fixture policy");
        };
    }
    public Map<String, Object> effects() {
        Map<String, Integer> counts = new TreeMap<>();
        executions.forEach((name, value) -> counts.put(name, value.get()));
        return Map.of("values", new TreeMap<>(values), "executions", counts, "writes", writes.get());
    }
    private int number(String name, int fallback) { return ((Number) config.getOrDefault(name, fallback)).intValue(); }
}

