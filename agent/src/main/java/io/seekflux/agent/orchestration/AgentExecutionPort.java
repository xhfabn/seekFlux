package io.seekflux.agent.orchestration;

import io.seekflux.agent.api.AgentSearchResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;

public interface AgentExecutionPort {

    AgentSearchResult execute(AgentExecutionRequest request);

    default AgentSearchResult execute(
            AgentExecutionRequest request, PushEventPublisher publisher) {
        return execute(request);
    }
}
