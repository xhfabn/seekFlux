package io.seekflux.platform.agentruntime.domain.model.capability;

import java.time.Instant;

public record CapabilityEvent(
        Type type,
        String agentId,
        String catalogVersion,
        Outcome outcome,
        Reason reason,
        int activeSkillCount,
        int activeToolGroupCount,
        int effectiveToolCount,
        Instant eventTime) {

    public enum Type {
        RESOLVED,
        TOOL_GROUPS_SWITCHED
    }

    public enum Outcome {
        ACCEPTED,
        REJECTED
    }

    public enum Reason {
        EXECUTION_START,
        VALIDATION,
        TOOL_RESULT
    }
}
