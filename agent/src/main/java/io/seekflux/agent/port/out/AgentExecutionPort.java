package io.seekflux.agent.port.out;

import io.seekflux.agent.port.in.AgentSearchResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;

public interface AgentExecutionPort {

    AgentSearchResult execute(AgentExecutionRequest request);

    default AgentSearchResult execute(
            AgentExecutionRequest request, PushEventPublisher publisher) {
        return execute(request);
    }
}
