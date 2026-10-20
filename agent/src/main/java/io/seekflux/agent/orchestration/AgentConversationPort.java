package io.seekflux.agent.orchestration;

import io.seekflux.agent.orchestration.SearchGoal;
import java.util.Optional;

public interface AgentConversationPort {

    Optional<SearchGoal> loadGoal(String sessionId);
}
