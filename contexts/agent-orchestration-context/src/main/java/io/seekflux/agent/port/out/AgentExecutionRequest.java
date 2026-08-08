package io.seekflux.agent.port.out;

import io.seekflux.agent.domain.SearchGoal;

public record AgentExecutionRequest(
        String requestId,
        String sessionId,
        String turnId,
        String agentId,
        SearchGoal goal,
        boolean allowClarification) {
}
