package io.seekflux.search.port.in;

import java.util.List;
import java.util.Objects;

public record SearchTrace(
        String requestId,
        String executionMode,
        String indexVersion,
        String policyVersion,
        long tookMillis,
        boolean degraded,
        List<String> unavailableSources,
        List<SearchChannelTrace> channels,
        String realtimeFeatureStatus,
        String realtimeFeatureVersion,
        java.time.Instant realtimeFeatureComputedAt) {

    public SearchTrace(
            String requestId,
            String executionMode,
            String indexVersion,
            String policyVersion,
            long tookMillis,
            boolean degraded,
            List<String> unavailableSources,
            List<SearchChannelTrace> channels) {
        this(requestId, executionMode, indexVersion, policyVersion, tookMillis, degraded,
                unavailableSources, channels, "MISSING", null, null);
    }

    public SearchTrace {
        Objects.requireNonNull(requestId, "request id must not be null");
        Objects.requireNonNull(executionMode, "execution mode must not be null");
        indexVersion = indexVersion == null ? "unavailable" : indexVersion;
        Objects.requireNonNull(policyVersion, "policy version must not be null");
        if (tookMillis < 0) {
            throw new IllegalArgumentException("search timing must not be negative");
        }
        unavailableSources = unavailableSources == null ? List.of() : List.copyOf(unavailableSources);
        channels = channels == null ? List.of() : List.copyOf(channels);
        realtimeFeatureStatus = realtimeFeatureStatus == null ? "MISSING" : realtimeFeatureStatus;
    }
}
