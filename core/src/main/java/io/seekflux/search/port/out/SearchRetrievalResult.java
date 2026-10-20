package io.seekflux.search.port.out;

import java.util.List;
import java.util.Objects;

public record SearchRetrievalResult(
        SearchRetrievalSource source,
        String indexVersion,
        String retrieverVersion,
        long tookMillis,
        long total,
        List<SearchCandidate> candidates) {

    public SearchRetrievalResult {
        Objects.requireNonNull(source, "retrieval source must not be null");
        Objects.requireNonNull(indexVersion, "index version must not be null");
        Objects.requireNonNull(retrieverVersion, "retriever version must not be null");
        if (tookMillis < 0 || total < 0) {
            throw new IllegalArgumentException("retrieval timing and total must not be negative");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
