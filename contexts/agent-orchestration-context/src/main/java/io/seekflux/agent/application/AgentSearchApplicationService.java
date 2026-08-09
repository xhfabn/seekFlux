package io.seekflux.agent.application;

import io.seekflux.agent.domain.ConstraintVersionConflictException;
import io.seekflux.agent.domain.QueryModeRouter;
import io.seekflux.agent.domain.QueryConstraintSet;
import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.agent.domain.SearchIntentAnalyzer;
import io.seekflux.agent.domain.SearchPlan;
import io.seekflux.agent.domain.SearchToolPolicy;
import io.seekflux.agent.port.in.AgentSearchCommand;
import io.seekflux.agent.port.in.AgentSearchResult;
import io.seekflux.agent.port.in.AgentSearchUseCase;
import io.seekflux.agent.port.out.AgentExecutionPort;
import io.seekflux.agent.port.out.AgentExecutionRequest;
import io.seekflux.agent.port.out.AgentConversationPort;
import io.seekflux.agent.port.out.DirectSearchPort;
import io.seekflux.agent.port.out.SearchGoalChange;
import java.util.List;
import java.util.Objects;

public final class AgentSearchApplicationService implements AgentSearchUseCase {

    private final AgentExecutionPort executor;
    private final DirectSearchPort directSearch;
    private final AgentConversationPort conversations;
    private final QueryModeRouter modeRouter;
    private final SearchIntentAnalyzer intentAnalyzer;
    private final SearchToolPolicy toolPolicy;

    public AgentSearchApplicationService(
            AgentExecutionPort executor,
            DirectSearchPort directSearch,
            AgentConversationPort conversations,
            QueryModeRouter modeRouter,
            SearchIntentAnalyzer intentAnalyzer,
            SearchToolPolicy toolPolicy) {
        this.executor = Objects.requireNonNull(executor, "agent execution port must not be null");
        this.directSearch = Objects.requireNonNull(directSearch, "direct search port must not be null");
        this.conversations = Objects.requireNonNull(conversations, "conversation port must not be null");
        this.modeRouter = Objects.requireNonNull(modeRouter, "query mode router must not be null");
        this.intentAnalyzer = Objects.requireNonNull(intentAnalyzer, "intent analyzer must not be null");
        this.toolPolicy = Objects.requireNonNull(toolPolicy, "tool policy must not be null");
    }

    @Override
    public AgentSearchResult search(AgentSearchCommand command) {
        Objects.requireNonNull(command, "agent search command must not be null");
        var current = conversations.loadGoal(command.sessionId());
        SearchGoal goal;
        long baseVersion;
        if (command.constraintPatch() != null) {
            SearchGoal previous = current.orElseThrow(() -> new ConstraintVersionConflictException(
                    command.constraintPatch().baseVersion(), 0));
            goal = previous.apply(command.constraintPatch());
            baseVersion = previous.version();
        } else if (current.isPresent()) {
            SearchGoal previous = current.get();
            goal = previous.replace(
                    command.query(),
                    new QueryConstraintSet(command.page(), command.size(), command.requiredTags()));
            baseVersion = previous.version();
        } else {
            goal = new SearchGoal(
                    command.query(),
                    new QueryConstraintSet(command.page(), command.size(), command.requiredTags()));
            baseVersion = 0;
        }
        SearchPlan plan = intentAnalyzer.analyze(goal);
        QueryModeRouter.Decision route = modeRouter.route(
                command.requestedMode(), command.constraintPatch() != null, plan);
        List<String> exposedTools = toolPolicy.exposedTools(plan);
        AgentExecutionRequest request = new AgentExecutionRequest(
                command.requestId(),
                command.sessionId(),
                command.turnId(),
                command.agentId(),
                command.query(),
                goal,
                plan,
                route.reason(),
                exposedTools,
                new SearchGoalChange(baseVersion, goal.toState()),
                command.allowClarification());
        return route.route() == QueryModeRouter.Route.DIRECT
                ? directSearch.execute(request)
                : executor.execute(request);
    }
}
