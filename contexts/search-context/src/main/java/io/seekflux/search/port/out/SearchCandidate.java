package io.seekflux.search.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SearchCandidate(
        String contentId,
        String creatorId,
        String mediaUri,
        String title,
        String description,
        String summary,
        List<String> tags,
        int profileVersion,
        double retrievalScore,
        Instant publishedAt) {

    public SearchCandidate {
        Objects.requireNonNull(contentId, "content id must not be null");
        creatorId = creatorId == null ? "" : creatorId;
        Objects.requireNonNull(mediaUri, "media URI must not be null");
        Objects.requireNonNull(title, "title must not be null");
        description = description == null ? "" : description;
        Objects.requireNonNull(summary, "summary must not be null");
        tags = tags == null ? List.of() : List.copyOf(tags);
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        if (!Double.isFinite(retrievalScore)) {
            throw new IllegalArgumentException("retrieval score must be finite");
        }
    }
}
