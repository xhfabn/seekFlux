package io.seekflux.search.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SearchDocument(
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
        String transcript,
        String sourceProvider,
        String sourcePageUri,
        String sourceAuthor,
        String licenseName,
        int profileVersion,
        Instant publishedAt) {

    public SearchDocument {
        Objects.requireNonNull(contentId, "content id must not be null");
        contentType = contentType == null || contentType.isBlank() ? "VIDEO" : contentType;
        Objects.requireNonNull(mediaUri, "media URI must not be null");
        assetUris = assetUris == null || assetUris.isEmpty() ? List.of(mediaUri) : List.copyOf(assetUris);
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        tags = tags == null ? List.of() : List.copyOf(tags);
        description = description == null ? "" : description;
        body = body == null ? "" : body;
        transcript = transcript == null ? "" : transcript;
        sourceProvider = sourceProvider == null ? "" : sourceProvider;
        sourcePageUri = sourcePageUri == null ? "" : sourcePageUri;
        sourceAuthor = sourceAuthor == null ? "" : sourceAuthor;
        licenseName = licenseName == null ? "" : licenseName;
    }

    public SearchDocument(
            String contentId, String creatorId, String mediaUri, String title, String description,
            String summary, List<String> tags, String transcript, int profileVersion,
            Instant publishedAt) {
        this(contentId, creatorId, "VIDEO", mediaUri, List.of(mediaUri), title, description,
                "", summary, tags, transcript, "", "", "", "", profileVersion, publishedAt);
    }
}
