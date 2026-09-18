package io.seekflux.platform.agentruntime.domain.model.feature;

import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilitySnapshot;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RuntimeContext {

    private final AgentDefinition definition;
    private final AgentRunRequest request;
    private final LlmClient llmClient;
    private final Map<String, Object> features;
    private final CapabilitySnapshot capabilities;
    private final Map<String, Object> params = new ConcurrentHashMap<>();

    public RuntimeContext(
            AgentDefinition definition,
            AgentRunRequest request,
            LlmClient llmClient,
            Map<String, Object> features) {
        this(definition, request, llmClient, features,
                legacyCapabilities(definition, request));
    }

    private static CapabilitySnapshot legacyCapabilities(
            AgentDefinition definition, AgentRunRequest request) {
        Object configured = request.attributes().get("allowedTools");
        if (!(configured instanceof java.util.List<?> values)) {
            return CapabilitySnapshot.legacy(definition.allowedTools());
        }
        java.util.Set<String> requested = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (requested.isEmpty() || !definition.allowedTools().containsAll(requested)) {
            throw new IllegalArgumentException("request contains an invalid dynamic tool set");
        }
        return CapabilitySnapshot.legacy(requested);
    }

    public RuntimeContext(
            AgentDefinition definition,
            AgentRunRequest request,
            LlmClient llmClient,
            Map<String, Object> features,
            CapabilitySnapshot capabilities) {
        this.definition = definition;
        this.request = request;
        this.llmClient = llmClient;
        this.features = features == null ? Map.of() : Map.copyOf(features);
        this.capabilities = capabilities == null
                ? CapabilitySnapshot.legacy(definition.allowedTools()) : capabilities;
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

    public CapabilitySnapshot capabilities() {
        return capabilities;
    }

    public Map<String, Object> params() {
        return params;
    }

    public RuntimeContext withPersistentFeatures(Map<String, Object> restoredFeatures) {
        return new RuntimeContext(definition, request, llmClient, restoredFeatures, capabilities);
    }

    public RuntimeContext forQueuedRequest(
            AgentRunRequest queuedRequest,
            Map<String, Object> persistentFeatures) {
        return new RuntimeContext(definition, queuedRequest, llmClient, persistentFeatures,
                capabilities);
    }

    public RuntimeContext forQueuedRequest(
            AgentRunRequest queuedRequest,
            Map<String, Object> persistentFeatures,
            CapabilitySnapshot queuedCapabilities) {
        return new RuntimeContext(definition, queuedRequest, llmClient, persistentFeatures,
                queuedCapabilities);
    }
}
