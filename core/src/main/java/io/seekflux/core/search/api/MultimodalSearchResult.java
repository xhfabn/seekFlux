package io.seekflux.core.search.api;

import io.seekflux.core.search.domain.MediaSearchCandidate;
import java.util.List;

public record MultimodalSearchResult(
        String queryModality,
        String modelVersion,
        int querySegments,
        List<String> retrievalChannels,
        boolean degraded,
        List<String> unavailableChannels,
        List<MediaSearchCandidate> items) {
}
