package io.seekflux.apps.contentserver.api;

import io.seekflux.content.domain.ContentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SubmitContentRequest(
        @NotBlank @Size(max = 128) String creatorId,
        ContentType contentType,
        @NotBlank @Size(max = 2_048) String mediaUri,
        @Size(max = 20) List<@NotBlank @Size(max = 2_048) String> assetUris,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4_000) String description,
        @Size(max = 100_000) String body,
        @Size(max = 50) List<@NotBlank @Size(max = 64) String> sourceTags,
        @Size(max = 64) String sourceProvider,
        @Size(max = 256) String externalId,
        @Size(max = 2_048) String sourcePageUri,
        @Size(max = 128) String sourceAuthor,
        @Size(max = 128) String licenseName) {
}
