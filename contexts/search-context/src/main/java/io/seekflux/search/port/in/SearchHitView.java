package io.seekflux.search.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SearchHitView(
        String contentId,
        String creatorId,
        String mediaUri,
        String title,
        String description,
        String summary,
        List<String> tags,
        int profileVersion,
        double score,
        Instant publishedAt) {

    public SearchHitView {
        Objects.requireNonNull(contentId, "content id must not be null");
        Objects.requireNonNull(mediaUri, "media URI must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
