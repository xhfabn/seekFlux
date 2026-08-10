package io.seekflux.platform.agentruntime.llm;

@FunctionalInterface
public interface AgentShadowRecorder {

    AgentShadowRecorder NOOP = observation -> { };

    void record(ShadowEvaluation observation);
}
