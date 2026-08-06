package io.seekflux.recommendation.port.in;

import java.util.List;

public record RecommendationPage(
        String requestId,
        List<RecommendationItemView> items,
        String nextCursor,
        boolean degraded,
        List<String> unavailableSources) {

    public RecommendationPage {
        items = items == null ? List.of() : List.copyOf(items);
        unavailableSources = unavailableSources == null ? List.of() : List.copyOf(unavailableSources);
    }
}
