package io.seekflux.agent.port.in;

import io.seekflux.agent.domain.ConstraintPatch;
import java.util.List;
import io.seekflux.platform.agentruntime.application.command.AgentIngressMode;

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
        ConstraintPatch constraintPatch,
        AgentIngressMode ingressMode,
        String tenantId,
        String userId) {

    public AgentSearchCommand {
        requestId = requireText(requestId, "request id");
        sessionId = requireText(sessionId, "session id");
        turnId = requireText(turnId, "turn id");
        agentId = requireText(agentId, "agent id");
        requiredTags = requiredTags == null ? List.of() : List.copyOf(requiredTags);
        requestedMode = requestedMode == null ? AgentRequestedMode.AUTO : requestedMode;
        ingressMode = ingressMode == null ? AgentIngressMode.NEW_EXECUTION : ingressMode;
        tenantId = optionalText(tenantId, "tenant id");
        userId = optionalText(userId, "user id");
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
            boolean allowClarification,
            AgentRequestedMode requestedMode,
            ConstraintPatch constraintPatch,
            AgentIngressMode ingressMode) {
        this(requestId, sessionId, turnId, agentId, query, page, size, requiredTags,
                allowClarification, requestedMode, constraintPatch, ingressMode, null, null);
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
            boolean allowClarification,
            AgentRequestedMode requestedMode,
            ConstraintPatch constraintPatch) {
        this(requestId, sessionId, turnId, agentId, query, page, size, requiredTags,
                allowClarification, requestedMode, constraintPatch,
                AgentIngressMode.NEW_EXECUTION, null, null);
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
                allowClarification, AgentRequestedMode.AUTO, null,
                AgentIngressMode.NEW_EXECUTION, null, null);
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

    private static String optionalText(String value, String name) {
        return value == null || value.isBlank() ? null : requireText(value, name);
    }
}
