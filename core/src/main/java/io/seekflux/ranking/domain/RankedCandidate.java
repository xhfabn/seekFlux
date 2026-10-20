package io.seekflux.ranking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record RankedCandidate(
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
        double score,
        Set<RetrievalSource> sources,
        String reason) {

    public RankedCandidate {
        contentType = contentType == null || contentType.isBlank() ? "VIDEO" : contentType;
        assetUris = assetUris == null || assetUris.isEmpty() ? List.of(mediaUri) : List.copyOf(assetUris);
        body = body == null ? "" : body;
        tags = tags == null ? List.of() : List.copyOf(tags);
        sourceProvider = sourceProvider == null ? "" : sourceProvider;
        sourcePageUri = sourcePageUri == null ? "" : sourcePageUri;
        sourceAuthor = sourceAuthor == null ? "" : sourceAuthor;
        licenseName = licenseName == null ? "" : licenseName;
        sources = sources == null ? Set.of() : Set.copyOf(sources);
        reason = reason == null ? "" : reason;
    }
}
