package io.seekflux.search.port.in;

import java.text.Normalizer;
import java.util.List;
import java.util.Objects;

public record SearchQuery(String text, int page, int size, List<String> requiredTags, String userId) {

    private static final int MAX_QUERY_LENGTH = 500;
    private static final int MAX_RESULT_WINDOW = 200;

    public SearchQuery(String text, int page, int size) {
        this(text, page, size, List.of(), "anonymous");
    }

    public SearchQuery(String text, int page, int size, List<String> requiredTags) {
        this(text, page, size, requiredTags, "anonymous");
    }

    public SearchQuery {
        text = normalize(text);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("search text must not be blank");
        }
        if (text.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("search text must not exceed 500 characters");
        }
        if (text.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint))) {
            throw new IllegalArgumentException("search text contains unsupported control characters");
        }
        if (page < 0) {
            throw new IllegalArgumentException("search page must not be negative");
        }
        if (size < 1 || size > 50) {
            throw new IllegalArgumentException("search size must be between 1 and 50");
        }
        long offset = (long) page * size;
        if (offset >= MAX_RESULT_WINDOW) {
            throw new IllegalArgumentException("search result window must stay below 200 candidates");
        }
        requiredTags = requiredTags == null ? List.of() : requiredTags.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        if (requiredTags.size() > 10) {
            throw new IllegalArgumentException("search required tags must not exceed 10 values");
        }
        if (requiredTags.stream().anyMatch(value -> value.length() > 64)) {
            throw new IllegalArgumentException("search required tags must not exceed 64 characters");
        }
        userId = userId == null || userId.isBlank() ? "anonymous" : userId.trim();
        if (userId.length() > 128) {
            throw new IllegalArgumentException("search user id must not exceed 128 characters");
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ");
    }
}
