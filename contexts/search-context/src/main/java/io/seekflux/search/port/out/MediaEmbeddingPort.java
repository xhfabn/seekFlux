package io.seekflux.search.port.out;

public interface MediaEmbeddingPort {
    MediaEmbeddingBatch embed(MediaModality modality, String input, int maxSegments);
}
