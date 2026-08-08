package io.seekflux.apps.onlineserver.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SearchRequest(
        @NotBlank @Size(max = 500) String query,
        @Min(0) Integer page,
        @Min(1) @Max(50) Integer size,
        @Size(max = 10) List<@Size(max = 64) String> requiredTags) {
}
