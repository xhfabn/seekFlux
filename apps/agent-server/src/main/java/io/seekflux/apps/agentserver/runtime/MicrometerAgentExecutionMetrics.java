package io.seekflux.apps.agentserver.runtime;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.seekflux.platform.agentruntime.AgentRunResult;
import java.util.concurrent.TimeUnit;

public final class MicrometerAgentExecutionMetrics implements AgentExecutionMetrics {

    private final MeterRegistry registry;

    public MicrometerAgentExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void succeeded(AgentRunResult result, long tookNanos) {
        var trace = result.trace();
        var definition = trace.definition();
        Tags tags = Tags.of(
                "agent", definition.id(),
                "agent_version", definition.version(),
                "prompt_version", definition.promptVersion(),
                "provider_version", definition.decisionProviderVersion(),
                "state", result.state().name(),
                "degraded", Boolean.toString(result.degraded()));
        registry.timer("seekflux.agent.request", tags).record(tookNanos, TimeUnit.NANOSECONDS);
        registry.counter("seekflux.agent.request.total", tags).increment();
        var usage = trace.llmUsage();
        if (usage.measured()) {
            registry.counter("seekflux.agent.llm.input.tokens", tags).increment(usage.inputTokens());
            registry.counter("seekflux.agent.llm.output.tokens", tags).increment(usage.outputTokens());
            registry.counter("seekflux.agent.llm.cost.micros", tags).increment(usage.costMicros());
        }
    }

    @Override
    public void failed(String agentId, Throwable error, long tookNanos) {
        Tags tags = Tags.of(
                "agent", agentId,
                "state", "ERROR",
                "error", error.getClass().getSimpleName());
        registry.timer("seekflux.agent.request", tags).record(tookNanos, TimeUnit.NANOSECONDS);
        registry.counter("seekflux.agent.request.total", tags).increment();
    }
}
