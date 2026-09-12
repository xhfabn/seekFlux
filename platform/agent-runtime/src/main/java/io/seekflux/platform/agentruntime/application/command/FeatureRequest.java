package io.seekflux.platform.agentruntime.application.command;

import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;

public record FeatureRequest(
        AgentDefinition definition,
        AgentRunRequest runRequest,
        LlmClient llmClient) {

    public FeatureRequest {
        if (definition == null || runRequest == null || llmClient == null) {
            throw new IllegalArgumentException("feature request fields must not be null");
        }
    }
}
