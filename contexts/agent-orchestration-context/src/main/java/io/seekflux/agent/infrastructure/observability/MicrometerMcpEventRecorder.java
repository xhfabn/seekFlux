package io.seekflux.agent.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.seekflux.agent.infrastructure.mcp.McpEvent;
import io.seekflux.agent.infrastructure.mcp.McpEventRecorder;

public final class MicrometerMcpEventRecorder implements McpEventRecorder {

    private final MeterRegistry registry;

    public MicrometerMcpEventRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void record(McpEvent event) {
        String tool = event.toolName() == null || event.toolName().isBlank()
                ? "none" : event.toolName();
        registry.counter(
                "seekflux.agent.mcp.events",
                "type", event.eventType().name(),
                "server", event.serverId(),
                "tool", tool,
                "outcome", event.outcome().name(),
                "reason", event.reason()).increment();
        registry.summary(
                "seekflux.agent.mcp.duration",
                "type", event.eventType().name(),
                "server", event.serverId(),
                "outcome", event.outcome().name()).record(event.durationMillis());
        if (event.eventType() == McpEvent.Type.DISCOVERY
                && event.outcome() == McpEvent.Outcome.SUCCEEDED) {
            registry.summary(
                    "seekflux.agent.mcp.active.tools",
                    "server", event.serverId()).record(event.activeToolCount());
        }
    }
}
