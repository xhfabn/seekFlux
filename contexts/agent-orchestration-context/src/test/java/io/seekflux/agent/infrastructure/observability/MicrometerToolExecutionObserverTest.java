package io.seekflux.agent.infrastructure.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.ToolExecutionObserver;
import org.junit.jupiter.api.Test;

class MicrometerToolExecutionObserverTest {

    @Test
    void recordsLowCardinalityLifecycleMetricsWithoutCallOrAttemptTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerToolExecutionObserver observer = new MicrometerToolExecutionObserver(registry);

        observer.observe(new ToolExecutionObserver.Event(
                ToolExecutionObserver.Phase.BEFORE,
                ToolExecutionObserver.Source.RECONCILIATION,
                "call-high-cardinality",
                "publish",
                AgentTool.Effect.MUTATING,
                "attempt-high-cardinality",
                0,
                "STARTED"));
        observer.observe(new ToolExecutionObserver.Event(
                ToolExecutionObserver.Phase.AFTER,
                ToolExecutionObserver.Source.RECONCILIATION,
                "call-high-cardinality",
                "publish",
                AgentTool.Effect.MUTATING,
                "attempt-high-cardinality",
                25,
                "RECONCILED_SUCCEEDED"));

        assertEquals(2, registry.find("seekflux.agent.tool.event.total").counters().size());
        assertEquals(1, registry.find("seekflux.agent.tool.duration").timers().size());
        assertEquals(0, registry.getMeters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .filter(tag -> tag.getKey().equals("tool_call_id")
                        || tag.getKey().equals("attempt_id"))
                .count());
    }
}
