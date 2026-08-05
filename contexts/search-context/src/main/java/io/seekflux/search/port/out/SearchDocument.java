package io.seekflux.search.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SearchDocument(
        String contentId,
        String creatorId,
        String mediaUri,
        String title,
        String description,
        String summary,
        List<String> tags,
        String transcript,
        int profileVersion,
        Instant publishedAt) {

    public SearchDocument {
        Objects.requireNonNull(contentId, "content id must not be null");
        Objects.requireNonNull(mediaUri, "media URI must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        tags = tags == null ? List.of() : List.copyOf(tags);
        description = description == null ? "" : description;
        transcript = transcript == null ? "" : transcript;
    }
}
