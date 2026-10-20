package io.seekflux.interaction.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InteractionSignal(
        UUID eventId,
        InteractionType eventType,
        String requestId,
        String traceId,
        UUID contentId,
        int position,
        InteractionSurface surface,
        Instant eventTime) {

    public InteractionSignal {
        Objects.requireNonNull(eventId, "event id must not be null");
        Objects.requireNonNull(eventType, "event type must not be null");
        requestId = requireText(requestId, "request id", 128);
        traceId = requireText(traceId, "trace id", 128);
        Objects.requireNonNull(contentId, "content id must not be null");
        if (position < 1 || position > 10_000) {
            throw new IllegalArgumentException("position must be between 1 and 10000");
        }
        Objects.requireNonNull(surface, "surface must not be null");
        Objects.requireNonNull(eventTime, "event time must not be null");
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
