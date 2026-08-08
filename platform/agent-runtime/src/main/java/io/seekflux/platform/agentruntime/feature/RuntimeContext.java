package io.seekflux.platform.agentruntime.feature;

import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.llm.LlmClient;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeContext {

    private final AgentDefinition definition;
    private final AgentRunRequest request;
    private final LlmClient llmClient;
    private final Map<String, Object> features;
    private final Map<String, Object> params = new ConcurrentHashMap<>();

    public RuntimeContext(
            AgentDefinition definition,
            AgentRunRequest request,
            LlmClient llmClient,
            Map<String, Object> features) {
        this.definition = definition;
        this.request = request;
        this.llmClient = llmClient;
        this.features = features == null ? Map.of() : Map.copyOf(features);
    }

    public AgentDefinition definition() {
        return definition;
    }

    public AgentRunRequest request() {
        return request;
    }

    public LlmClient llmClient() {
        return llmClient;
    }

    public Map<String, Object> features() {
        return features;
    }

    public Map<String, Object> params() {
        return params;
    }
}
