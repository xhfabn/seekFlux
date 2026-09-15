package io.seekflux.agent.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.ToolExecutionObserver;
import java.util.concurrent.TimeUnit;

public final class MicrometerToolExecutionObserver implements ToolExecutionObserver {

    private final MeterRegistry registry;

    public MicrometerToolExecutionObserver(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void observe(Event event) {
        String outcome = switch (event.phase()) {
            case BEFORE -> "STARTED";
            case AFTER -> "SUCCEEDED";
            case FAILURE -> "FAILED";
        };
        Tags tags = Tags.of(
                "tool", event.toolName(),
                "effect", event.effect().name(),
                "source", event.source().name(),
                "phase", event.phase().name(),
                "outcome", outcome);
        registry.counter("seekflux.agent.tool.event.total", tags).increment();
        if (event.phase() != Phase.BEFORE) {
            registry.timer("seekflux.agent.tool.duration", tags)
                    .record(event.durationMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
