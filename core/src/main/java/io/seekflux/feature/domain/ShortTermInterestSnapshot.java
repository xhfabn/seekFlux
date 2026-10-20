package io.seekflux.feature.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record ShortTermInterestSnapshot(
        String userId,
        List<FeatureTopicScore> topics,
        Instant windowStart,
        Instant windowEnd,
        Instant computedAt,
        String featureVersion) {

    public ShortTermInterestSnapshot {
        userId = requireText(userId, "userId");
        topics = topics == null ? List.of() : List.copyOf(topics);
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");
        Objects.requireNonNull(computedAt, "computedAt must not be null");
        featureVersion = requireText(featureVersion, "featureVersion");
        if (windowStart.isAfter(windowEnd)) {
            throw new IllegalArgumentException("feature window start must not be after end");
        }
        if (topics.size() > 10) {
            throw new IllegalArgumentException("short-term interest must not contain more than 10 topics");
        }
    }

    private static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
