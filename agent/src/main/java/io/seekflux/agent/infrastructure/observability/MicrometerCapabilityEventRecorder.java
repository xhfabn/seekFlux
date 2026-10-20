package io.seekflux.agent.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.seekflux.platform.agentruntime.application.spi.capability.event.CapabilityEventRecorder;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityEvent;

public final class MicrometerCapabilityEventRecorder implements CapabilityEventRecorder {

    private final MeterRegistry registry;

    public MicrometerCapabilityEventRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(CapabilityEvent event) {
        Tags tags = Tags.of(
                "agent", event.agentId(),
                "catalog_version", event.catalogVersion(),
                "event", event.type().name(),
                "outcome", event.outcome().name(),
                "reason", event.reason().name());
        registry.counter("seekflux.agent.capability.event.total", tags).increment();
        registry.summary("seekflux.agent.capability.active.skills", tags)
                .record(event.activeSkillCount());
        registry.summary("seekflux.agent.capability.active.tool.groups", tags)
                .record(event.activeToolGroupCount());
        registry.summary("seekflux.agent.capability.effective.tools", tags)
                .record(event.effectiveToolCount());
    }
}
