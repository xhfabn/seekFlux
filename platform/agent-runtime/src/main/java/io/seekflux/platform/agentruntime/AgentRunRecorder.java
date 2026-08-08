package io.seekflux.platform.agentruntime;

@FunctionalInterface
public interface AgentRunRecorder {

    AgentRunRecorder NOOP = event -> { };

    void record(AgentRunEvent event);
}
