package io.seekflux.search.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SearchHitView(
        String contentId,
        String creatorId,
        String contentType,
        String mediaUri,
        List<String> assetUris,
        String title,
        String description,
        String body,
        String summary,
        List<String> tags,
        String sourceProvider,
        String sourcePageUri,
        String sourceAuthor,
        String licenseName,
        int profileVersion,
        double score,
        List<String> sources,
        Instant publishedAt) {

    public SearchHitView {
        Objects.requireNonNull(contentId, "content id must not be null");
        contentType = contentType == null || contentType.isBlank() ? "VIDEO" : contentType;
        Objects.requireNonNull(mediaUri, "media URI must not be null");
        assetUris = assetUris == null || assetUris.isEmpty() ? List.of(mediaUri) : List.copyOf(assetUris);
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        body = body == null ? "" : body;
        tags = tags == null ? List.of() : List.copyOf(tags);
        sources = sources == null ? List.of() : List.copyOf(sources);
        sourceProvider = sourceProvider == null ? "" : sourceProvider;
        sourcePageUri = sourcePageUri == null ? "" : sourcePageUri;
        sourceAuthor = sourceAuthor == null ? "" : sourceAuthor;
        licenseName = licenseName == null ? "" : licenseName;
    }
}
