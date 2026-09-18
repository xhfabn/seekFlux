package io.seekflux.agent.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MicrometerCapabilityEventRecorderTest {

    @Test
    void recordsOnlyControlledCapabilityDimensions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerCapabilityEventRecorder recorder =
                new MicrometerCapabilityEventRecorder(registry);

        recorder.record(new CapabilityEvent(
                CapabilityEvent.Type.RESOLVED,
                "search-assistant",
                "catalog-v1",
                CapabilityEvent.Outcome.ACCEPTED,
                CapabilityEvent.Reason.EXECUTION_START,
                2,
                2,
                3,
                Instant.parse("2026-09-17T00:00:00Z")));

        assertThat(registry.get("seekflux.agent.capability.event.total")
                .tags(
                        "agent", "search-assistant",
                        "catalog_version", "catalog-v1",
                        "event", "RESOLVED",
                        "outcome", "ACCEPTED",
                        "reason", "EXECUTION_START")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("seekflux.agent.capability.effective.tools")
                .summary().totalAmount()).isEqualTo(3.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().contains("skill_id")));
    }
}
