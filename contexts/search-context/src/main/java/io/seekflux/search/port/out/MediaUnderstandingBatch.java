package io.seekflux.search.port.out;

import java.util.List;
import java.util.Map;

public record MediaUnderstandingBatch(
        MediaEmbeddingBatch embeddings,
        List<MediaUnderstandingEvidence> evidence,
        Map<String, String> channelStatuses) {

    public MediaUnderstandingBatch {
        if (embeddings == null) throw new IllegalArgumentException("understanding embeddings are required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        channelStatuses = channelStatuses == null ? Map.of() : Map.copyOf(channelStatuses);
    }
}
