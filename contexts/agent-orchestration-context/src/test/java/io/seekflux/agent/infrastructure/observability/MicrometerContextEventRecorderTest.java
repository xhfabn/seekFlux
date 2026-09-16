package io.seekflux.agent.infrastructure.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MicrometerContextEventRecorderTest {

    @Test
    void recordsOnlyLowCardinalityContextLifecycleTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerContextEventRecorder recorder = new MicrometerContextEventRecorder(registry);

        recorder.record(new ContextEvent(
                ContextEvent.Type.OVERFLOW_RETRY,
                "high-cardinality-session",
                "high-cardinality-request",
                9_000,
                4_096,
                "HTTP_413",
                Instant.EPOCH));

        assertThat(registry.get("seekflux.agent.context.event.total")
                .tags("event", "OVERFLOW_RETRY", "reason", "HTTP_413")
                .counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).noneMatch(tag ->
                        tag.getValue().contains("high-cardinality")));
    }
}
