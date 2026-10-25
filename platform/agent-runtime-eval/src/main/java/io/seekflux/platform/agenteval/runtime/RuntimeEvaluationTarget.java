package io.seekflux.platform.agenteval.runtime;

import io.seekflux.platform.agenteval.*;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.ToolExecutionObserver;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunEvent;
import io.seekflux.platform.agentruntime.domain.service.execution.AgentCallGuard;
import io.seekflux.platform.agentruntime.domain.service.runtime.AgentRuntime;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static io.seekflux.platform.agenteval.Evaluation.Phase.*;

/** Runs the production Runtime with fresh tools, planner, policy, cancellation and evidence capture. */
public final class RuntimeEvaluationTarget implements EvaluationTarget {
    private final String id;
    private final String version;
    private final RuntimeFixture.Factory fixtures;
    public RuntimeEvaluationTarget(String id, String version, RuntimeFixture.Factory fixtures) {
        this.id = Objects.requireNonNull(id); this.version = Objects.requireNonNull(version);
        this.fixtures = Objects.requireNonNull(fixtures);
    }
    public String id() { return id; }
    public String version() { return version; }
    public Set<String> capabilities() {
        return Set.of("basic", "schema", "policy", "parallel", "cancellation", "tool-faults", "model-faults", "in-memory-effects");
    }
    public Session open(Evaluation.Case c, FaultController faults) throws Exception {
        RuntimeFixture fixture = fixtures.create(c);
        return new Session() {
            final ExecutorService calls = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());
            final CancellationToken cancellation = new CancellationToken();
            public Evaluation.Evidence execute() throws Exception {
                var registry = new AgentToolRegistry(fixture.tools().stream().map(t -> new FaultInjectingTool(t, faults)).toList());
                List<AgentRunEvent> events = new CopyOnWriteArrayList<>();
                AtomicInteger models = new AtomicInteger();
                var runtime = new AgentRuntime(registry, new DefaultAgentToolExecutor(registry), calls,
                        events::add, Clock.systemUTC(), AgentCallGuard.UNBOUNDED, fixture.policy(), ToolExecutionObserver.NOOP);
                String execution = UUID.randomUUID().toString();
                var request = new AgentRunRequest(execution, "eval-session-" + execution, "eval-turn-" + execution, c.input(), Map.of());
                var planner = fixture.planner();
                var result = runtime.run(fixture.definition(), request, context -> {
                    faults.at(BEFORE_MODEL, null, cancellation);
                    models.incrementAndGet();
                    var decision = planner.decide(context);
                    faults.at(AFTER_MODEL, null, cancellation);
                    return decision;
                }, cancellation);
                stopCalls();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("state", result.state().name());
                data.put("output", result.output());
                data.put("fallbackReason", result.fallbackReason());
                data.put("cancellationReason", result.cancellationReason());
                data.put("clarification", result.clarification());
                data.put("messages", EvalJson.tree(result.messages()));
                data.put("events", EvalJson.tree(events));
                data.put("trace", EvalJson.tree(result.trace()));
                data.put("effects", EvalJson.tree(fixture.effects()));
                data.put("modelCalls", models.get());
                data.put("eventTypes", events.stream().map(e -> e.type().name()).toList());
                data.put("providerUsageMeasured", result.trace().llmUsage().measured());
                data.put("totalTokens", result.trace().llmUsage().measured() ? result.trace().llmUsage().totalTokens() : null);
                data.put("costMicros", result.trace().llmUsage().measured() ? result.trace().llmUsage().costMicros() : null);
                return new Evaluation.Evidence(data);
            }
            private void stopCalls() throws InterruptedException {
                calls.shutdownNow();
                if (!calls.awaitTermination(2, TimeUnit.SECONDS))
                    throw new IllegalStateException("runtime calls did not stop; effect snapshot is not reliable");
            }
            public void close() throws Exception {
                cancellation.cancel(false);
                try { stopCalls(); } finally { fixture.close(); }
            }
        };
    }
}

