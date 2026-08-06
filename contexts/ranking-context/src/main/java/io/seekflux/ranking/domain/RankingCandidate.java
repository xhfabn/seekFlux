package io.seekflux.ranking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RankingCandidate(
        String contentId,
        String creatorId,
        String mediaUri,
        String title,
        String description,
        String summary,
        List<String> tags,
        int profileVersion,
        Instant publishedAt,
        RetrievalSource source,
        int sourceRank,
        double retrievalScore) {

    public RankingCandidate {
        contentId = requireText(contentId, "content id");
        creatorId = requireText(creatorId, "creator id");
        mediaUri = requireText(mediaUri, "media URI");
        title = requireText(title, "title");
        description = description == null ? "" : description;
        summary = requireText(summary, "summary");
        tags = tags == null ? List.of() : List.copyOf(tags);
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

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
