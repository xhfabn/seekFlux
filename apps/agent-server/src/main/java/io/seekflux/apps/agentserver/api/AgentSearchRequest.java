package io.seekflux.apps.agentserver.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.seekflux.agent.port.in.AgentRequestedMode;
import java.util.List;

public record AgentSearchRequest(
        @Size(max = 128) String requestId,
        @NotBlank @Size(max = 128) String sessionId,
        @NotBlank @Size(max = 128) String turnId,
        @Size(max = 128) String agentId,
        @NotBlank @Size(max = 500) String query,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size,
        @Size(max = 10) List<@Size(max = 64) String> requiredTags,
        AgentRequestedMode mode,
        @Valid ConstraintPatchRequest constraintPatch,
        @Valid Options options) {

    public record Options(Boolean allowClarification) {
    }

    public record ConstraintPatchRequest(
            @NotNull @Min(1) Long baseVersion,
            @Size(max = 500) String replacementQuery,
            @Min(0) Integer page,
            @Min(1) @Max(50) Integer size,
            @Size(max = 10) List<@Size(max = 64) String> addRequiredTags,
            @Size(max = 10) List<@Size(max = 64) String> removeRequiredTags) {
    }
}
