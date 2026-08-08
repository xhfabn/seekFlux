package io.seekflux.apps.agentserver.api;

import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.agent.port.in.AgentExecutionMode;
import io.seekflux.agent.port.in.AgentSearchResult;
import io.seekflux.agent.port.in.AgentSearchState;
import io.seekflux.agent.port.in.AgentTraceView;
import io.seekflux.search.port.in.SearchHitView;
import io.seekflux.search.port.in.SearchTrace;
import java.util.List;

public record AgentSearchResponse(
        String requestId,
        String agentRunId,
        String sessionId,
        String turnId,
        AgentSearchState state,
        AgentExecutionMode executionMode,
        QueryConstraintSet appliedConstraints,
        String clarification,
        long total,
        int page,
        int size,
        List<SearchHitView> items,
        SearchTrace searchTrace,
        AgentTraceView agentTrace,
        boolean degraded,
        String fallbackReason) {

    public static AgentSearchResponse from(AgentSearchResult result) {
        var search = result.searchResult();
        return new AgentSearchResponse(
                result.requestId(),
                result.agentRunId(),
                result.sessionId(),
                result.turnId(),
                result.state(),
                result.executionMode(),
                result.appliedConstraints(),
                result.clarification(),
                search == null ? 0 : search.total(),
                search == null ? result.appliedConstraints().page() : search.page(),
                search == null ? result.appliedConstraints().size() : search.size(),
                search == null ? List.of() : search.hits(),
                search == null ? null : search.trace(),
                result.trace(),
                result.degraded(),
                result.fallbackReason());
    }
}
