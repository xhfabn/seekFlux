package io.seekflux.search.port.out;

import java.util.List;

public record MediaEmbeddingSegment(
        int ordinal,
        long startMillis,
        long endMillis,
        String previewUri,
        List<Double> vector) {

    public MediaEmbeddingSegment {
        if (ordinal < 0 || startMillis < 0 || endMillis < startMillis) {
            throw new IllegalArgumentException("invalid media embedding segment range");
        }
        previewUri = previewUri == null ? "" : previewUri.trim();
        vector = vector == null ? List.of() : List.copyOf(vector);
        if (vector.isEmpty()) {
            throw new IllegalArgumentException("media embedding vector must not be empty");
        }
    }
}
