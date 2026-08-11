package io.seekflux.search.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SearchCandidate(
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
        double retrievalScore,
        Instant publishedAt) {

    public SearchCandidate {
        Objects.requireNonNull(contentId, "content id must not be null");
        creatorId = creatorId == null ? "" : creatorId;
        contentType = contentType == null || contentType.isBlank() ? "VIDEO" : contentType;
        Objects.requireNonNull(mediaUri, "media URI must not be null");
        assetUris = assetUris == null || assetUris.isEmpty() ? List.of(mediaUri) : List.copyOf(assetUris);
        Objects.requireNonNull(title, "title must not be null");
        description = description == null ? "" : description;
        body = body == null ? "" : body;
        Objects.requireNonNull(summary, "summary must not be null");
        tags = tags == null ? List.of() : List.copyOf(tags);
        sourceProvider = sourceProvider == null ? "" : sourceProvider;
        sourcePageUri = sourcePageUri == null ? "" : sourcePageUri;
        sourceAuthor = sourceAuthor == null ? "" : sourceAuthor;
        licenseName = licenseName == null ? "" : licenseName;
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        if (!Double.isFinite(retrievalScore)) {
            throw new IllegalArgumentException("retrieval score must be finite");
        }
    }

    public SearchCandidate(
            String contentId, String creatorId, String mediaUri, String title, String description,
            String summary, List<String> tags, int profileVersion, double retrievalScore,
            Instant publishedAt) {
        this(contentId, creatorId, "VIDEO", mediaUri, List.of(mediaUri), title, description,
                "", summary, tags, "", "", "", "", profileVersion, retrievalScore, publishedAt);
    }
}
