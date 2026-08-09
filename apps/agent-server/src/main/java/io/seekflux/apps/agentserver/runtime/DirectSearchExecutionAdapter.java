package io.seekflux.apps.agentserver.runtime;

import io.seekflux.agent.port.in.AgentExecutionMode;
import io.seekflux.agent.port.in.AgentSearchResult;
import io.seekflux.agent.port.in.AgentSearchState;
import io.seekflux.agent.port.out.AgentExecutionRequest;
import io.seekflux.agent.port.out.DirectSearchPort;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchUseCase;

public final class DirectSearchExecutionAdapter implements DirectSearchPort {

    private final SearchUseCase search;

    public DirectSearchExecutionAdapter(SearchUseCase search) {
        this.search = search;
    }

    @Override
    public AgentSearchResult execute(AgentExecutionRequest request) {
        SearchResultPage result = search.search(request.goal().toSearchQuery());
        return new AgentSearchResult(
                request.requestId(),
                null,
                request.sessionId(),
                request.turnId(),
                AgentSearchState.RESULTS_READY,
                AgentExecutionMode.DIRECT,
                0,
                request.routeReason(),
                request.plan(),
                request.goal().constraints(),
                null,
                result,
                null,
                0,
                false,
                result.trace().degraded(),
                null,
                null);
    }
}
