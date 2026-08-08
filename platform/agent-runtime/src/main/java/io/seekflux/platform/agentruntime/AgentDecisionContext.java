package io.seekflux.platform.agentruntime;

import java.time.Duration;
import java.util.List;

public record AgentDecisionContext(
        AgentRunRequest request,
        int step,
        Duration remaining,
        List<AgentToolObservation> observations) {

    public AgentDecisionContext {
        observations = observations == null ? List.of() : List.copyOf(observations);
    }
}
