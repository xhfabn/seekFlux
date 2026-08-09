package io.seekflux.agent.port.out;

import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.agent.domain.SearchPlan;
import java.util.List;

public record AgentExecutionRequest(
        String requestId,
        String sessionId,
        String turnId,
        String agentId,
        String userInput,
        SearchGoal goal,
        SearchPlan plan,
        String routeReason,
        List<String> exposedTools,
        SearchGoalChange goalChange,
        boolean allowClarification) {

    public AgentExecutionRequest {
        exposedTools = exposedTools == null ? List.of() : List.copyOf(exposedTools);
    }
}
