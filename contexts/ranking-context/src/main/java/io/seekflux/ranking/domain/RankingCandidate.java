package io.seekflux.ranking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RankingCandidate(
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
        Instant publishedAt,
        RetrievalSource source,
        int sourceRank,
        double retrievalScore) {

    public RankingCandidate {
        contentId = requireText(contentId, "content id");
        creatorId = requireText(creatorId, "creator id");
        contentType = requireText(contentType, "content type");
        mediaUri = requireText(mediaUri, "media URI");
        assetUris = assetUris == null || assetUris.isEmpty() ? List.of(mediaUri) : List.copyOf(assetUris);
        title = requireText(title, "title");
        description = description == null ? "" : description;
        body = body == null ? "" : body;
        summary = requireText(summary, "summary");
        tags = tags == null ? List.of() : List.copyOf(tags);
        sourceProvider = sourceProvider == null ? "" : sourceProvider;
        sourcePageUri = sourcePageUri == null ? "" : sourcePageUri;
        sourceAuthor = sourceAuthor == null ? "" : sourceAuthor;
        licenseName = licenseName == null ? "" : licenseName;
        publishedAt = Objects.requireNonNull(publishedAt, "publishedAt must not be null");
        source = Objects.requireNonNull(source, "retrieval source must not be null");
        if (profileVersion < 1) {
            throw new IllegalArgumentException("profile version must be positive");
        }
        if (sourceRank < 1) {
            throw new IllegalArgumentException("source rank must be positive");
        }
        if (!Double.isFinite(retrievalScore)) {
            throw new IllegalArgumentException("retrieval score must be finite");
        }
    }

    public RankingCandidate(
            String contentId, String creatorId, String mediaUri, String title, String description,
            String summary, List<String> tags, int profileVersion, Instant publishedAt,
            RetrievalSource source, int sourceRank, double retrievalScore) {
        this(contentId, creatorId, "VIDEO", mediaUri, List.of(mediaUri), title, description,
                "", summary, tags, "", "", "", "", profileVersion, publishedAt, source,
                sourceRank, retrievalScore);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
