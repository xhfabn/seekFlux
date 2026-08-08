package io.seekflux.platform.agentruntime.execution;

import java.util.Optional;

public interface ExecutionAuthorityStore {

    Optional<ExecutionAuthority> acquire(String sessionId, String ownerToken, long ttlMillis);

    boolean isHeld(String sessionId);
}
