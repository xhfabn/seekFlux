package io.seekflux.platform.agentruntime.application.spi.capability.session;

import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
import java.time.Instant;
import java.util.Optional;

public interface AgentSessionStore {

    Optional<AgentSession> restoreFresh(String sessionId);

    AgentSession createIfAbsent(String sessionId, AgentDefinition definition, Instant eventTime);

    IngressCommitResult commitIngress(AgentRunRequest request, long fencingToken, Instant eventTime);

    void appendOutcome(String sessionId, AgentRunResult result, long fencingToken, Instant eventTime);
}
