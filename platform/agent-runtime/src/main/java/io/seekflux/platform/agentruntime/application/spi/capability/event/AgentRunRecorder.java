package io.seekflux.platform.agentruntime.application.spi.capability.event;

import io.seekflux.platform.agentruntime.domain.model.run.AgentRunEvent;

@FunctionalInterface
public interface AgentRunRecorder {

    AgentRunRecorder NOOP = event -> { };

    void record(AgentRunEvent event);
}
