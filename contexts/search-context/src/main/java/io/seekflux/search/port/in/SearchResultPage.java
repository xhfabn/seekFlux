package io.seekflux.search.port.in;

import java.util.List;

public record SearchResultPage(
        String query,
        long total,
        int page,
        int size,
        long tookMillis,
        List<SearchHitView> hits) {

    public SearchResultPage {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }
}
