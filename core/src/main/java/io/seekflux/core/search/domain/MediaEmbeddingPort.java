package io.seekflux.core.search.domain;

public interface MediaEmbeddingPort {
    MediaEmbeddingBatch embed(MediaModality modality, String input, int maxSegments);
}
