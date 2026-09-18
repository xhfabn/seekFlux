package io.seekflux.platform.agentruntime.domain.model.capability;

public record CapabilityUpdateResult(Status status, CapabilityActivationState state) {

    public enum Status {
        UPDATED,
        DUPLICATE,
        NOT_FOUND,
        BUSY,
        CONFLICT
    }
}
