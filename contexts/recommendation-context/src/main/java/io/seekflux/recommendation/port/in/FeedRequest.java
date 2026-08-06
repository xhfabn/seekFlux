package io.seekflux.recommendation.port.in;

import java.util.List;
import java.util.Objects;

public record FeedRequest(
        String userId,
        List<String> explicitInterests,
        String seedContentId,
        String cursor,
        int pageSize) {

    public FeedRequest {
        userId = requireText(userId, "user id", 128);
        explicitInterests = explicitInterests == null ? List.of() : List.copyOf(explicitInterests);
        seedContentId = normalizeOptional(seedContentId);
        cursor = normalizeOptional(cursor);
        if (pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("feed page size must be between 1 and 50");
        }
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
