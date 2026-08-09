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
        AgentTraceView trace) {
}
