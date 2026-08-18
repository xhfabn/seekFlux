package io.seekflux.search.port.out;

public interface MediaUnderstandingPort {
    MediaUnderstandingBatch understand(MediaModality modality, String input, int maxSegments);
}
