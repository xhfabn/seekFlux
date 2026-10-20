package io.seekflux.search.port.out;

import java.util.List;

public record MediaEmbeddingBatch(String modelVersion, int dimensions, List<MediaEmbeddingSegment> segments) {
    public MediaEmbeddingBatch {
        modelVersion = modelVersion == null ? "" : modelVersion.trim();
        if (modelVersion.isEmpty() || dimensions < 1) {
            throw new IllegalArgumentException("media embedding model metadata is required");
        }
        segments = segments == null ? List.of() : List.copyOf(segments);
        if (segments.isEmpty() || segments.stream().anyMatch(segment -> segment.vector().size() != dimensions)) {
            throw new IllegalArgumentException("media embedding dimensions are inconsistent");
        }
    }
}
