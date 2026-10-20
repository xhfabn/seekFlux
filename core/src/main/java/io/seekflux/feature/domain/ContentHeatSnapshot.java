package io.seekflux.feature.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ContentHeatSnapshot(
        UUID contentId,
        double score,
        long eventCount,
        Instant windowStart,
        Instant windowEnd,
        Instant computedAt,
        String featureVersion) {

    public ContentHeatSnapshot {
        Objects.requireNonNull(contentId, "contentId must not be null");
        if (!Double.isFinite(score)) {
            throw new IllegalArgumentException("content heat score must be finite");
        }
        if (eventCount < 0) {
            throw new IllegalArgumentException("content heat event count must not be negative");
        }
        Objects.requireNonNull(windowStart, "windowStart must not be null");
        Objects.requireNonNull(windowEnd, "windowEnd must not be null");
        Objects.requireNonNull(computedAt, "computedAt must not be null");
        featureVersion = featureVersion == null ? "" : featureVersion.trim();
        if (featureVersion.isEmpty() || windowStart.isAfter(windowEnd)) {
            throw new IllegalArgumentException("invalid content heat window or version");
        }
    }
}
