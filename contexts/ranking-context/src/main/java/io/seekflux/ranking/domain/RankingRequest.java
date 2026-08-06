package io.seekflux.ranking.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record RankingRequest(List<String> interestTopics, int limit, Instant rankedAt) {

    public RankingRequest {
        var normalized = new LinkedHashSet<String>();
        if (interestTopics != null) {
            interestTopics.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(topic -> !topic.isEmpty())
                    .map(topic -> topic.toLowerCase(Locale.ROOT))
                    .forEach(normalized::add);
        }
        interestTopics = List.copyOf(normalized);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("ranking limit must be between 1 and 500");
        }
        rankedAt = Objects.requireNonNull(rankedAt, "rankedAt must not be null");
    }
}
