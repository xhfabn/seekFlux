package io.seekflux.feature.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record FeatureProjectionResult(
        UUID eventId,
        FeatureProjectionDisposition disposition,
        Instant watermark,
        String reason) {

    public FeatureProjectionResult {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(disposition, "disposition must not be null");
        Objects.requireNonNull(watermark, "watermark must not be null");
    }
}
