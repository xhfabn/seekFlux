package io.seekflux.content.port.out;

import io.seekflux.content.domain.ContentId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ContentEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        ContentId contentId,
        Instant eventTime,
        Map<String, Object> payload) {

    public ContentEvent {
        Objects.requireNonNull(eventId, "event id must not be null");
        Objects.requireNonNull(eventType, "event type must not be null");
        Objects.requireNonNull(contentId, "content id must not be null");
        Objects.requireNonNull(eventTime, "event time must not be null");
        payload = Map.copyOf(payload);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schema version must be positive");
        }
    }
}
