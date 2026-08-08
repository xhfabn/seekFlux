package io.seekflux.agent.port.in;

import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.search.port.in.SearchResultPage;

public record AgentSearchResult(
        String requestId,
        String agentRunId,
        String sessionId,
        String turnId,
        AgentSearchState state,
        AgentExecutionMode executionMode,
        QueryConstraintSet appliedConstraints,
        String clarification,
        SearchResultPage searchResult,
        boolean degraded,
        String fallbackReason,
        AgentTraceView trace) {
}
