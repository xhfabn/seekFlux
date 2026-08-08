package io.seekflux.agent.application;

import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.agent.port.in.AgentSearchCommand;
import io.seekflux.agent.port.in.AgentSearchResult;
import io.seekflux.agent.port.in.AgentSearchUseCase;
import io.seekflux.agent.port.out.AgentExecutionPort;
import io.seekflux.agent.port.out.AgentExecutionRequest;
import java.util.Objects;

public final class AgentSearchApplicationService implements AgentSearchUseCase {

    private final AgentExecutionPort executor;

    public AgentSearchApplicationService(AgentExecutionPort executor) {
        this.executor = Objects.requireNonNull(executor, "agent execution port must not be null");
    }

    @Override
    public AgentSearchResult search(AgentSearchCommand command) {
        Objects.requireNonNull(command, "agent search command must not be null");
        SearchGoal goal = new SearchGoal(
                command.query(),
                new QueryConstraintSet(command.page(), command.size(), command.requiredTags()));
        return executor.execute(new AgentExecutionRequest(
                command.requestId(),
                command.sessionId(),
                command.turnId(),
                command.agentId(),
                goal,
                command.allowClarification()));
    }
}
