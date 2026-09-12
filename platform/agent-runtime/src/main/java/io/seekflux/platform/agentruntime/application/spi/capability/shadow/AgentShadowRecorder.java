package io.seekflux.platform.agentruntime.application.spi.capability.shadow;

import io.seekflux.platform.agentruntime.application.spi.capability.shadow.model.ShadowEvaluation;

@FunctionalInterface
public interface AgentShadowRecorder {

    AgentShadowRecorder NOOP = observation -> { };

    void record(ShadowEvaluation observation);
}
