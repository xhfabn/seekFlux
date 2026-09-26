package io.seekflux.agent.port.out;

import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.agent.domain.SearchPlan;
import java.util.List;
import io.seekflux.platform.agentruntime.application.command.AgentIngressMode;

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
        boolean allowClarification,
        AgentIngressMode ingressMode,
        String tenantId,
        String userId) {

    public AgentExecutionRequest {
        exposedTools = exposedTools == null ? List.of() : List.copyOf(exposedTools);
        ingressMode = ingressMode == null ? AgentIngressMode.NEW_EXECUTION : ingressMode;
    }

    public AgentExecutionRequest(
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
            boolean allowClarification,
            AgentIngressMode ingressMode) {
        this(requestId, sessionId, turnId, agentId, userInput, goal, plan, routeReason,
                exposedTools, goalChange, allowClarification, ingressMode, null, null);
    }

    public AgentExecutionRequest(
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
        this(requestId, sessionId, turnId, agentId, userInput, goal, plan, routeReason,
                exposedTools, goalChange, allowClarification, AgentIngressMode.NEW_EXECUTION,
                null, null);
    }
}
