package io.seekflux.agent.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextEventRecorder;
import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;

public final class MicrometerContextEventRecorder implements ContextEventRecorder {

    private final MeterRegistry registry;

    public MicrometerContextEventRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(ContextEvent event) {
        Tags tags = Tags.of(
                "event", event.type().name(),
                "reason", event.reason());
        registry.counter("seekflux.agent.context.event.total", tags).increment();
        registry.summary("seekflux.agent.context.estimated.tokens", tags)
                .record(event.estimatedTokens());
        if (event.budgetTokens() < Integer.MAX_VALUE) {
            registry.summary("seekflux.agent.context.budget.tokens", tags)
                    .record(event.budgetTokens());
        }
    }
}
