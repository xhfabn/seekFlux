package io.seekflux.platform.agentruntime.session;

import io.seekflux.platform.agentruntime.AgentDefinition;
import io.seekflux.platform.agentruntime.AgentRunRequest;
import io.seekflux.platform.agentruntime.AgentRunResult;
import java.time.Instant;
import java.util.Optional;

public interface AgentSessionStore {

    Optional<AgentSession> restoreFresh(String sessionId);

    AgentSession createIfAbsent(String sessionId, AgentDefinition definition, Instant eventTime);

    IngressCommitResult commitIngress(AgentRunRequest request, Instant eventTime);

    void appendOutcome(String sessionId, AgentRunResult result, Instant eventTime);
}
