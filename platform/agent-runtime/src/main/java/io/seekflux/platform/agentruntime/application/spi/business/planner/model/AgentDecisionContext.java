package io.seekflux.platform.agentruntime.application.spi.business.planner.model;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.message.AgentAssistantContent;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilitySnapshot;

public record AgentDecisionContext(
        AgentRunRequest request,
        int step,
        Duration remaining,
        List<AgentToolObservation> observations,
        Consumer<LlmUsage> usageRecorder,
        Consumer<AgentAssistantContent> assistantContentRecorder,
        String agentRunId,
        EagerToolDispatcher eagerToolDispatcher,
        CapabilitySnapshot capabilities,
        Map<String, String> toolSchemaVersions) {

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations,
            Consumer<LlmUsage> usageRecorder,
            Consumer<AgentAssistantContent> assistantContentRecorder,
            String agentRunId,
            EagerToolDispatcher eagerToolDispatcher,
            CapabilitySnapshot capabilities) {
        this(request, step, remaining, observations, usageRecorder, assistantContentRecorder,
                agentRunId, eagerToolDispatcher, capabilities, Map.of());
    }

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations,
            Consumer<LlmUsage> usageRecorder,
            Consumer<AgentAssistantContent> assistantContentRecorder,
            String agentRunId,
            EagerToolDispatcher eagerToolDispatcher) {
        this(request, step, remaining, observations, usageRecorder, assistantContentRecorder,
                agentRunId, eagerToolDispatcher, null);
    }

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations) {
        this(request, step, remaining, observations, ignored -> { }, ignored -> { }, null,
                EagerToolDispatcher.DISABLED, null);
    }

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations,
            Consumer<LlmUsage> usageRecorder) {
        this(request, step, remaining, observations, usageRecorder, ignored -> { }, null,
                EagerToolDispatcher.DISABLED, null);
    }

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations,
            Consumer<LlmUsage> usageRecorder,
            Consumer<AgentAssistantContent> assistantContentRecorder) {
        this(request, step, remaining, observations,
                usageRecorder, assistantContentRecorder, null, EagerToolDispatcher.DISABLED, null);
    }

    public AgentDecisionContext(
            AgentRunRequest request,
            int step,
            Duration remaining,
            List<AgentToolObservation> observations,
            Consumer<LlmUsage> usageRecorder,
            Consumer<AgentAssistantContent> assistantContentRecorder,
            String agentRunId) {
        this(request, step, remaining, observations, usageRecorder,
                assistantContentRecorder, agentRunId, EagerToolDispatcher.DISABLED, null);
    }

    public AgentDecisionContext {
        observations = observations == null ? List.of() : List.copyOf(observations);
        usageRecorder = usageRecorder == null ? ignored -> { } : usageRecorder;
        assistantContentRecorder = assistantContentRecorder == null ? ignored -> { } : assistantContentRecorder;
        agentRunId = agentRunId == null ? "" : agentRunId;
        eagerToolDispatcher = eagerToolDispatcher == null
                ? EagerToolDispatcher.DISABLED : eagerToolDispatcher;
        toolSchemaVersions = toolSchemaVersions == null ? Map.of() : Map.copyOf(toolSchemaVersions);
    }

    public void recordUsage(LlmUsage usage) {
        usageRecorder.accept(usage == null ? LlmUsage.UNMEASURED : usage);
    }

    public void recordAssistantContent(AgentAssistantContent assistantContent) {
        assistantContentRecorder.accept(
                assistantContent == null ? AgentAssistantContent.EMPTY : assistantContent);
    }

    public EagerToolDispatcher.Dispatch dispatchEagerTool(
            int index,
            String toolName,
            java.util.Map<String, Object> arguments) {
        return eagerToolDispatcher.dispatch(index, toolName, arguments);
    }
}
