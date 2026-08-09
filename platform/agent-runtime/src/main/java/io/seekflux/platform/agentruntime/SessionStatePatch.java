package io.seekflux.platform.agentruntime;

import java.util.Map;

public record SessionStatePatch(long baseVersion, Map<String, Object> state) {

    public SessionStatePatch {
        if (baseVersion < 0) {
            throw new IllegalArgumentException("session state base version must not be negative");
        }
        state = state == null ? Map.of() : Map.copyOf(state);
        if (state.isEmpty()) {
            throw new IllegalArgumentException("session state patch must not be empty");
        }
    }

    public long nextVersion() {
        return baseVersion + 1;
    }
}
