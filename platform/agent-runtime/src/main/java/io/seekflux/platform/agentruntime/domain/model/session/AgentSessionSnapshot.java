package io.seekflux.platform.agentruntime.domain.model.session;

import io.seekflux.platform.agentruntime.domain.model.capability.CapabilityActivationState;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** State baseline plus references, never a copy of the message/event bodies. */
public record AgentSessionSnapshot(int schemaVersion, String sessionId, String agentId, String agentVersion,
        long position, long stateVersion, Map<String, Object> workspaceState, AgentSessionStatus status,
        CapabilityActivationState capabilities, Set<String> pendingToolCallIds, List<Long> retainedEventPositions) {
    public AgentSessionSnapshot {
        if (schemaVersion != 1 || position < 0 || stateVersion < 0 || status == null) {
            throw new IllegalArgumentException("invalid Session snapshot coordinates");
        }
        workspaceState = Map.copyOf(workspaceState);
        capabilities = capabilities == null ? CapabilityActivationState.EMPTY : capabilities;
        pendingToolCallIds = Set.copyOf(pendingToolCallIds);
        retainedEventPositions = List.copyOf(retainedEventPositions);
    }
}
