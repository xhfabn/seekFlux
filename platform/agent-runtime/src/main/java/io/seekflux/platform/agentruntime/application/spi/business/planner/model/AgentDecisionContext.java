package io.seekflux.platform.agentruntime.application.spi.business.planner.model;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.message.AgentAssistantContent;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public record AgentDecisionContext(
        AgentRunRequest request,
        int step,
        Duration remaining,
        List<AgentToolObservation> observations,
        Consumer<LlmUsage> usageRecorder,
        Consumer<AgentAssistantContent> assistantContentRecorder) {

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations) {
        this(request, step, remaining, observations, ignored -> { }, ignored -> { });
    }

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations,
            Consumer<LlmUsage> usageRecorder) {
        this(request, step, remaining, observations, usageRecorder, ignored -> { });
    }

    public AgentDecisionContext {
        observations = observations == null ? List.of() : List.copyOf(observations);
        usageRecorder = usageRecorder == null ? ignored -> { } : usageRecorder;
        assistantContentRecorder = assistantContentRecorder == null ? ignored -> { } : assistantContentRecorder;
    }

    public void recordUsage(LlmUsage usage) {
        usageRecorder.accept(usage == null ? LlmUsage.UNMEASURED : usage);
    }

    public void recordAssistantContent(AgentAssistantContent assistantContent) {
        assistantContentRecorder.accept(
                assistantContent == null ? AgentAssistantContent.EMPTY : assistantContent);
    }
}
