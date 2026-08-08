package io.seekflux.search.port.in;

import java.util.List;

public record SearchResultPage(
        String query,
        long total,
        int page,
        int size,
        long tookMillis,
        List<SearchHitView> hits,
        SearchTrace trace) {

    public SearchResultPage {
        hits = hits == null ? List.of() : List.copyOf(hits);
        if (tookMillis < 0 || total < 0) {
            throw new IllegalArgumentException("search timing and total must not be negative");
        }
        if (trace == null) {
            throw new IllegalArgumentException("search trace must not be null");
        }
    }
}
