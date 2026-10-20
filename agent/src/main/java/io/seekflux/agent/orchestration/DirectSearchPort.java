package io.seekflux.agent.orchestration;

import io.seekflux.agent.api.AgentSearchResult;

public interface DirectSearchPort {

    AgentSearchResult execute(AgentExecutionRequest request);
}
