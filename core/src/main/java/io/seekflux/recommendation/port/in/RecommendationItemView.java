package io.seekflux.recommendation.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record RecommendationItemView(
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
        Set<String> sources,
        String reason) {

    public RecommendationItemView {
        contentType = contentType == null || contentType.isBlank() ? "VIDEO" : contentType;
        assetUris = assetUris == null || assetUris.isEmpty() ? List.of(mediaUri) : List.copyOf(assetUris);
        body = body == null ? "" : body;
        tags = tags == null ? List.of() : List.copyOf(tags);
        sourceProvider = sourceProvider == null ? "" : sourceProvider;
        sourcePageUri = sourcePageUri == null ? "" : sourcePageUri;
        sourceAuthor = sourceAuthor == null ? "" : sourceAuthor;
        licenseName = licenseName == null ? "" : licenseName;
        sources = sources == null ? Set.of() : Set.copyOf(sources);
    }
}
