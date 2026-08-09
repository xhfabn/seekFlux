package io.seekflux.agent.port.out;

import io.seekflux.agent.domain.SearchGoal;
import java.util.Optional;

public interface AgentConversationPort {

    Optional<SearchGoal> loadGoal(String sessionId);
}
