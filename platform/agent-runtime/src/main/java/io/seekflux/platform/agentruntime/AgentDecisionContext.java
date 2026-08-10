package io.seekflux.platform.agentruntime;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import io.seekflux.platform.agentruntime.llm.LlmUsage;

public record AgentDecisionContext(
        AgentRunRequest request,
        int step,
        Duration remaining,
        List<AgentToolObservation> observations,
        Consumer<LlmUsage> usageRecorder) {

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations) {
        this(request, step, remaining, observations, ignored -> { });
    }

    public AgentDecisionContext {
        observations = observations == null ? List.of() : List.copyOf(observations);
        usageRecorder = usageRecorder == null ? ignored -> { } : usageRecorder;
    }

    public void recordUsage(LlmUsage usage) {
        usageRecorder.accept(usage == null ? LlmUsage.UNMEASURED : usage);
    }
}
