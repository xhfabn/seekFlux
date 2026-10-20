package io.seekflux.agent.port.out;

import java.util.Map;

public record SearchGoalChange(long baseVersion, Map<String, Object> state) {

    public SearchGoalChange {
        if (baseVersion < 0) {
            throw new IllegalArgumentException("search goal base version must not be negative");
        }
        state = state == null ? Map.of() : Map.copyOf(state);
    }
}
