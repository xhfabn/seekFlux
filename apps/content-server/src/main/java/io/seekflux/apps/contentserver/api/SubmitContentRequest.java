package io.seekflux.apps.contentserver.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SubmitContentRequest(
        @NotBlank @Size(max = 128) String creatorId,
        @NotBlank @Size(max = 2_048) String mediaUri,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4_000) String description,
        @Size(max = 50) List<@NotBlank @Size(max = 64) String> sourceTags) {
}
