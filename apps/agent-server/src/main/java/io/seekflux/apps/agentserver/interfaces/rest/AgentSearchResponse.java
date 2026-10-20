package io.seekflux.apps.agentserver.interfaces.rest;

import io.seekflux.agent.orchestration.QueryConstraintSet;
import io.seekflux.agent.orchestration.SearchPlan;
import io.seekflux.agent.api.AgentExecutionMode;
import io.seekflux.agent.api.AgentSearchResult;
import io.seekflux.agent.api.AgentSearchState;
import io.seekflux.agent.api.AgentTraceView;
import io.seekflux.core.search.api.SearchHitView;
import io.seekflux.core.search.api.SearchTrace;
import java.util.List;

public record AgentSearchResponse(
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
        long total,
        int page,
        int size,
        List<SearchHitView> items,
        SearchTrace searchTrace,
        AgentTraceView agentTrace,
        String selectedTool,
        int successfulToolCount,
        boolean candidateSetReused,
        boolean degraded,
        String fallbackReason,
        String cancellationReason,
        int queueDepth,
        String waitId,
        String waitType) {

    public static AgentSearchResponse from(AgentSearchResult result) {
        var search = result.searchResult();
        return new AgentSearchResponse(
                result.requestId(),
                result.agentRunId(),
                result.sessionId(),
                result.turnId(),
                result.state(),
                result.executionMode(),
                result.goalVersion(),
                result.routeReason(),
                result.searchPlan(),
                result.appliedConstraints(),
                result.clarification(),
                search == null ? 0 : search.total(),
                search == null ? result.appliedConstraints().page() : search.page(),
                search == null ? result.appliedConstraints().size() : search.size(),
                search == null ? List.of() : search.hits(),
                search == null ? null : search.trace(),
                result.trace(),
                result.selectedTool(),
                result.successfulToolCount(),
                result.candidateSetReused(),
                result.degraded(),
                result.fallbackReason(),
                result.cancellationReason(),
                result.queueDepth(),
                result.waitId(),
                result.waitType());
    }
}
