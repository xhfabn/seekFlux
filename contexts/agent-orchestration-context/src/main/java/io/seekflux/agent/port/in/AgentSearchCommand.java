package io.seekflux.agent.port.in;

import io.seekflux.agent.domain.ConstraintPatch;
import java.util.List;

public record AgentSearchCommand(
        String requestId,
        String sessionId,
        String turnId,
        String agentId,
        String query,
        int page,
        int size,
        List<String> requiredTags,
        boolean allowClarification,
        AgentRequestedMode requestedMode,
        ConstraintPatch constraintPatch) {

    public AgentSearchCommand {
        requestId = requireText(requestId, "request id");
        sessionId = requireText(sessionId, "session id");
        turnId = requireText(turnId, "turn id");
        agentId = requireText(agentId, "agent id");
        requiredTags = requiredTags == null ? List.of() : List.copyOf(requiredTags);
        requestedMode = requestedMode == null ? AgentRequestedMode.AUTO : requestedMode;
    }

    public AgentSearchCommand(
            String requestId,
            String sessionId,
            String turnId,
            String agentId,
            String query,
            int page,
            int size,
            List<String> requiredTags,
            boolean allowClarification) {
        this(requestId, sessionId, turnId, agentId, query, page, size, requiredTags,
                allowClarification, AgentRequestedMode.AUTO, null);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException(name + " must not exceed 128 characters");
        }
        return normalized;
    }
}
