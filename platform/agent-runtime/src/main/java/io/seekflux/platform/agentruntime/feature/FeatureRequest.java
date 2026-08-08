package io.seekflux.platform.agentruntime.feature;

import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.llm.LlmClient;

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
