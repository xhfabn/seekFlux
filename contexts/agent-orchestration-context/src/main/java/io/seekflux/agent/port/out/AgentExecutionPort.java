package io.seekflux.agent.port.out;

import io.seekflux.agent.port.in.AgentSearchResult;

public interface AgentExecutionPort {

    AgentSearchResult execute(AgentExecutionRequest request);
}
