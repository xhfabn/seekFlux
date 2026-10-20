package io.seekflux.agent.port.in;

import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.agent.domain.SearchPlan;
import io.seekflux.search.port.in.SearchResultPage;

public record AgentSearchResult(
        String requestId,
        String agentRunId,
        String sessionId,
        String turnId,
        AgentSearchState state,
        AgentExecutionMode executionMode,
        long goalVersion,
        String routeReason,
        SearchPlan searchPlan,
        QueryConstraintSet appliedConstraints,
        String clarification,
        SearchResultPage searchResult,
        String selectedTool,
        int successfulToolCount,
        boolean candidateSetReused,
        boolean degraded,
        String fallbackReason,
        String cancellationReason,
        AgentTraceView trace,
        int queueDepth,
        String waitId,
        String waitType) {

    public AgentSearchResult(
            String requestId,
            String agentRunId,
            String sessionId,
            String turnId,
            AgentSearchState state,
            AgentExecutionMode executionMode,
            long goalVersion,
            String routeReason,
            SearchPlan searchPlan,
            QueryConstraintSet appliedConstraints,
            String clarification,
            SearchResultPage searchResult,
            String selectedTool,
            int successfulToolCount,
            boolean candidateSetReused,
            boolean degraded,
            String fallbackReason,
            String cancellationReason,
            AgentTraceView trace,
            int queueDepth) {
        this(requestId, agentRunId, sessionId, turnId, state, executionMode, goalVersion,
                routeReason, searchPlan, appliedConstraints, clarification, searchResult,
                selectedTool, successfulToolCount, candidateSetReused, degraded,
                fallbackReason, cancellationReason, trace, queueDepth, null, null);
    }

    public AgentSearchResult(
            String requestId,
            String agentRunId,
            String sessionId,
            String turnId,
            AgentSearchState state,
            AgentExecutionMode executionMode,
            long goalVersion,
            String routeReason,
            SearchPlan searchPlan,
            QueryConstraintSet appliedConstraints,
            String clarification,
            SearchResultPage searchResult,
            String selectedTool,
            int successfulToolCount,
            boolean candidateSetReused,
            boolean degraded,
            String fallbackReason,
            String cancellationReason,
            AgentTraceView trace) {
        this(requestId, agentRunId, sessionId, turnId, state, executionMode, goalVersion,
                routeReason, searchPlan, appliedConstraints, clarification, searchResult,
                selectedTool, successfulToolCount, candidateSetReused, degraded,
                fallbackReason, cancellationReason, trace, 0, null, null);
    }

    public AgentSearchResult {
        if (queueDepth < 0) {
            throw new IllegalArgumentException("queue depth must not be negative");
        }
    }
}
