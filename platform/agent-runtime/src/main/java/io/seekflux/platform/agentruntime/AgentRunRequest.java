package io.seekflux.platform.agentruntime;

import java.util.Map;

public record AgentRunRequest(
        String requestId,
        String sessionId,
        String turnId,
        String input,
        Map<String, Object> attributes) {

    public AgentRunRequest {
        requestId = requireText(requestId, "request id", 128);
        sessionId = requireText(sessionId, "session id", 128);
        turnId = requireText(turnId, "turn id", 128);
        input = requireText(input, "agent input", 500);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
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
