package io.seekflux.agent.port.in;

import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;

public interface AgentSearchUseCase {

    AgentSearchResult search(AgentSearchCommand command);

    default AgentSearchResult search(
            AgentSearchCommand command, PushEventPublisher publisher) {
        return search(command);
    }
}
