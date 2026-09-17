package io.seekflux.platform.agentruntime.domain.model.wait;

public record WaitResolutionResult(
        Status status,
        WaitState waitState,
        WaitResolution resolution) {

    public WaitResolutionResult {
        if (status == null) {
            throw new IllegalArgumentException("wait resolution status is required");
        }
    }

    public static WaitResolutionResult committed(
            WaitState waitState, WaitResolution resolution) {
        return new WaitResolutionResult(Status.COMMITTED, waitState, resolution);
    }

    public static WaitResolutionResult duplicate(
            WaitState waitState, WaitResolution resolution) {
        return new WaitResolutionResult(Status.DUPLICATE, waitState, resolution);
    }

    public static WaitResolutionResult conflict(
            WaitState waitState, WaitResolution resolution) {
        return new WaitResolutionResult(Status.CONFLICT, waitState, resolution);
    }

    public static WaitResolutionResult missing() {
        return new WaitResolutionResult(Status.MISSING, null, null);
    }

    public enum Status {
        COMMITTED,
        DUPLICATE,
        CONFLICT,
        MISSING
    }
}
