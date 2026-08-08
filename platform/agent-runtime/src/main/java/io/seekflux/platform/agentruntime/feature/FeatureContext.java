package io.seekflux.platform.agentruntime.feature;

import io.seekflux.platform.agentruntime.session.AgentSession;
import java.util.HashMap;
import java.util.Map;

public final class FeatureContext {

    private final FeatureRequest request;
    private final Map<String, Object> persistentAttributes = new HashMap<>();
    private final Map<String, Object> transientAttributes = new HashMap<>();
    private AgentSession session;
    private RuntimeContext runtimeContext;
    private String resumeAction;

    public FeatureContext(FeatureRequest request) {
        this.request = request;
    }

    public FeatureRequest request() {
        return request;
    }

    public AgentSession session() {
        return session;
    }

    public void session(AgentSession session) {
        this.session = session;
    }

    public RuntimeContext runtimeContext() {
        return runtimeContext;
    }

    public void runtimeContext(RuntimeContext runtimeContext) {
        this.runtimeContext = runtimeContext;
    }

    public String resumeAction() {
        return resumeAction;
    }

    public void resumeAction(String resumeAction) {
        this.resumeAction = resumeAction;
    }

    public void setAttribute(String key, Object value) {
        persistentAttributes.put(key, value);
    }

    public void setTransientAttribute(String key, Object value) {
        transientAttributes.put(key, value);
    }

    public Map<String, Object> persistentAttributes() {
        return Map.copyOf(persistentAttributes);
    }

    public Map<String, Object> transientAttributes() {
        return Map.copyOf(transientAttributes);
    }
}
