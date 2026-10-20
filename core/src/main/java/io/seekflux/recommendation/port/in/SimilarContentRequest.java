package io.seekflux.recommendation.port.in;

import java.util.List;
import java.util.Objects;

public record SimilarContentRequest(
        String contentId,
        String userId,
        List<String> explicitInterests,
        String cursor,
        int pageSize) {

    public SimilarContentRequest {
        contentId = requireText(contentId, "content id");
        userId = requireText(userId, "user id");
        explicitInterests = explicitInterests == null ? List.of() : List.copyOf(explicitInterests);
        cursor = cursor == null || cursor.isBlank() ? null : cursor.trim();
        if (pageSize < 1 || pageSize > 50) {
            throw new IllegalArgumentException("similar content page size must be between 1 and 50");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
