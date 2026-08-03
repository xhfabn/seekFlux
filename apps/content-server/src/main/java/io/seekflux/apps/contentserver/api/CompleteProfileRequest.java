package io.seekflux.apps.contentserver.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CompleteProfileRequest(
        @Positive int profileVersion,
        @NotBlank @Size(max = 4_000) String summary,
        @Size(max = 50) List<@NotBlank @Size(max = 64) String> tags,
        @Size(max = 50_000) String transcript) {
}
