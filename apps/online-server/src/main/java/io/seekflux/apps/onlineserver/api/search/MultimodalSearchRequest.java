package io.seekflux.apps.onlineserver.api.search;

import io.seekflux.core.search.domain.MediaModality;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MultimodalSearchRequest(
        @NotNull MediaModality modality,
        @NotBlank @Size(max = 4096) String input,
        @Min(1) @Max(50) Integer size) {
}
