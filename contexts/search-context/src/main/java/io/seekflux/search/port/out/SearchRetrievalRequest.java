package io.seekflux.search.port.out;

import java.util.List;
import java.util.Objects;

public record SearchRetrievalRequest(
        SearchRetrievalSource source,
        String query,
        List<String> requiredTags,
        int limit) {

    public SearchRetrievalRequest {
        Objects.requireNonNull(source, "retrieval source must not be null");
        query = query == null ? "" : query.trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("retrieval query must not be blank");
        }
        requiredTags = requiredTags == null ? List.of() : List.copyOf(requiredTags);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("retrieval limit must be between 1 and 500");
        }
    }
}
