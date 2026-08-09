package io.seekflux.apps.agentserver.runtime;

import io.seekflux.agent.domain.SearchGoal;
import io.seekflux.agent.port.out.AgentConversationPort;
import io.seekflux.platform.agentruntime.session.AgentSessionStore;
import java.util.Optional;

public final class AgentSessionGoalAdapter implements AgentConversationPort {

    private final AgentSessionStore sessions;

    public AgentSessionGoalAdapter(AgentSessionStore sessions) {
        this.sessions = sessions;
    }

    @Override
    public Optional<SearchGoal> loadGoal(String sessionId) {
        return sessions.restoreFresh(sessionId)
                .filter(session -> !session.workspaceState().isEmpty())
                .map(session -> SearchGoal.fromState(session.workspaceState()));
    }
}
