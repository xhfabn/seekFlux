package io.seekflux.ranking.domain;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record RankedCandidate(
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
        Set<RetrievalSource> sources,
        String reason) {

    public RankedCandidate {
        tags = tags == null ? List.of() : List.copyOf(tags);
        sources = sources == null ? Set.of() : Set.copyOf(sources);
        reason = reason == null ? "" : reason;
    }
}
