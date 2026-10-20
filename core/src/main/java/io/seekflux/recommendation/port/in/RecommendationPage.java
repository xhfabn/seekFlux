package io.seekflux.recommendation.port.in;

import java.util.List;
import java.time.Instant;

public record RecommendationPage(
        String requestId,
        List<RecommendationItemView> items,
        String nextCursor,
        boolean degraded,
        List<String> unavailableSources,
        String realtimeFeatureStatus,
        String realtimeFeatureVersion,
        Instant realtimeFeatureComputedAt) {

    public RecommendationPage(
            String requestId,
            List<RecommendationItemView> items,
            String nextCursor,
            boolean degraded,
            List<String> unavailableSources) {
        this(requestId, items, nextCursor, degraded, unavailableSources, "MISSING", null, null);
    }

    public RecommendationPage {
        items = items == null ? List.of() : List.copyOf(items);
        unavailableSources = unavailableSources == null ? List.of() : List.copyOf(unavailableSources);
        realtimeFeatureStatus = realtimeFeatureStatus == null ? "MISSING" : realtimeFeatureStatus;
    }
}
