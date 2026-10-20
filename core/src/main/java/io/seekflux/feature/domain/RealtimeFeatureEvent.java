package io.seekflux.feature.domain;

import io.seekflux.interaction.domain.InteractionType;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record RealtimeFeatureEvent(
        UUID eventId,
        String userId,
        InteractionType eventType,
        UUID contentId,
        List<String> contentTags,
        Instant eventTime,
        Instant ingestedAt) {

    public RealtimeFeatureEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        userId = requireText(userId, "userId", 128);
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(contentId, "contentId must not be null");
        contentTags = normalizeTags(contentTags);
        Objects.requireNonNull(eventTime, "eventTime must not be null");
        Objects.requireNonNull(ingestedAt, "ingestedAt must not be null");
    }

    private static List<String> normalizeTags(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String tag = value.trim().toLowerCase(Locale.ROOT);
            if (tag.length() > 64) {
                throw new IllegalArgumentException("content tag must not exceed 64 characters");
            }
            normalized.add(tag);
        }
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
