package io.seekflux.search.port.in;

public record SearchQuery(String text, int page, int size) {

    public SearchQuery {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("search text must not be blank");
        }
        if (text.length() > 500) {
            throw new IllegalArgumentException("search text must not exceed 500 characters");
        }
        if (page < 0) {
            throw new IllegalArgumentException("search page must not be negative");
        }
        if (size < 1 || size > 50) {
            throw new IllegalArgumentException("search size must be between 1 and 50");
        }
    }
}
