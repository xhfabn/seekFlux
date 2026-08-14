package io.seekflux.search.port.in;

import io.seekflux.search.port.out.MediaSearchCandidate;
import java.util.List;

public record MultimodalSearchResult(
        String queryModality,
        String modelVersion,
        int querySegments,
        List<MediaSearchCandidate> items) {
}
