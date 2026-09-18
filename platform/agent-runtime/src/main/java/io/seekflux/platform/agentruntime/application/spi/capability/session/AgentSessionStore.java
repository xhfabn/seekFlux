package io.seekflux.platform.agentruntime.application.spi.capability.session;

import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.QueueCommitResult;
import io.seekflux.platform.agentruntime.domain.model.session.QueuedMessageBatch;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityChange;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityUpdateResult;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;

public interface AgentSessionStore {

    Optional<AgentSession> restoreFresh(String sessionId);

    AgentSession createIfAbsent(String sessionId, AgentDefinition definition, Instant eventTime);

    IngressCommitResult commitIngress(AgentRunRequest request, long fencingToken, Instant eventTime);

    default QueueCommitResult enqueue(
            AgentRunRequest request, int maxQueueDepth, Instant eventTime) {
        throw new UnsupportedOperationException("agent message queue is not configured");
    }

    default QueueCommitResult enqueue(
            AgentRunRequest request,
            Map<String, Object> persistentFeatures,
            int maxQueueDepth,
            Instant eventTime) {
        return enqueue(request, maxQueueDepth, eventTime);
    }

    default Optional<QueuedMessageBatch> promoteQueued(
            String sessionId, long fencingToken, Instant eventTime) {
        return Optional.empty();
    }

    void appendOutcome(String sessionId, AgentRunResult result, long fencingToken, Instant eventTime);

    default CapabilityUpdateResult updateCapabilities(
            String sessionId,
            CapabilityChange change,
            CapabilityActivationState updatedState,
            String actor,
            Instant eventTime) {
        throw new UnsupportedOperationException("Session capability activation is not configured");
    }
}
