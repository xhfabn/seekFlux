package io.seekflux.core.search.domain;

public interface MediaUnderstandingPort {
    MediaUnderstandingBatch understand(MediaModality modality, String input, int maxSegments);
}
