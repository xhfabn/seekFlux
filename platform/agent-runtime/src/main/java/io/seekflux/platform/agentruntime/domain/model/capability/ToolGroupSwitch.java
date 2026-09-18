package io.seekflux.platform.agentruntime.domain.model.capability;

import java.util.Set;

public record ToolGroupSwitch(Set<String> activeGroups) {

    public ToolGroupSwitch {
        activeGroups = activeGroups == null ? Set.of() : Set.copyOf(activeGroups);
        if (activeGroups.size() > 64) {
            throw new IllegalArgumentException("active ToolGroup count must not exceed 64");
        }
    }
}
