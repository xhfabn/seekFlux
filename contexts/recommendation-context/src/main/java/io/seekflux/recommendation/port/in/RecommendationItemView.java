package io.seekflux.recommendation.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record RecommendationItemView(
        String contentId,
        String creatorId,
        String mediaUri,
        String title,
        String description,
        String summary,
        List<String> tags,
        int profileVersion,
        Instant publishedAt,
        double score,
        Set<String> sources,
        String reason) {

    public RecommendationItemView {
        tags = tags == null ? List.of() : List.copyOf(tags);
        sources = sources == null ? Set.of() : Set.copyOf(sources);
    }
}
