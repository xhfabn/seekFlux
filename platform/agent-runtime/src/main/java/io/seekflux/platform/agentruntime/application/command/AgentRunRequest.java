package io.seekflux.platform.agentruntime.application.command;

import io.seekflux.platform.agentruntime.domain.model.session.SessionStatePatch;
import java.util.Map;

public record AgentRunRequest(
        String requestId,
        String sessionId,
        String turnId,
        String input,
        Map<String, Object> attributes,
        SessionStatePatch statePatch,
        AgentIngressMode ingressMode,
        CapabilityRequest capabilities) {

    public AgentRunRequest {
        requestId = requireText(requestId, "request id", 128);
        sessionId = requireText(sessionId, "session id", 128);
        turnId = requireText(turnId, "turn id", 128);
        input = requireText(input, "agent input", 500);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        ingressMode = ingressMode == null ? AgentIngressMode.NEW_EXECUTION : ingressMode;
        capabilities = capabilities == null ? CapabilityRequest.NONE : capabilities;
    }

    public AgentRunRequest(
            String requestId,
            String sessionId,
            String turnId,
            String input,
            Map<String, Object> attributes,
            SessionStatePatch statePatch,
            AgentIngressMode ingressMode) {
        this(requestId, sessionId, turnId, input, attributes, statePatch, ingressMode,
                CapabilityRequest.NONE);
    }

    public AgentRunRequest(
            String requestId,
            String sessionId,
            String turnId,
            String input,
            Map<String, Object> attributes,
            SessionStatePatch statePatch) {
        this(requestId, sessionId, turnId, input, attributes, statePatch,
                AgentIngressMode.NEW_EXECUTION, CapabilityRequest.NONE);
    }

    public AgentRunRequest(
            String requestId,
            String sessionId,
            String turnId,
            String input,
            Map<String, Object> attributes) {
        this(requestId, sessionId, turnId, input, attributes, null,
                AgentIngressMode.NEW_EXECUTION, CapabilityRequest.NONE);
    }

    public AgentRunRequest withAttributes(Map<String, Object> sanitizedAttributes) {
        return new AgentRunRequest(
                requestId, sessionId, turnId, input, sanitizedAttributes, statePatch, ingressMode,
                capabilities);
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
