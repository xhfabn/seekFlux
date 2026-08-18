package io.seekflux.search.port.out;

import java.time.Instant;
import java.util.List;

public record MediaSegmentDocument(
        String contentId,
        String contentType,
        String mediaUri,
        List<String> assetUris,
        String title,
        String summary,
        List<String> tags,
        int segmentOrdinal,
        long startMillis,
        long endMillis,
        String previewUri,
        String modelVersion,
        List<Double> vector,
        String understandingText,
        List<MediaUnderstandingEvidence> evidence,
        java.util.Map<String, String> channelStatuses,
        Instant publishedAt) {

    public MediaSegmentDocument {
        assetUris = assetUris == null ? List.of() : List.copyOf(assetUris);
        tags = tags == null ? List.of() : List.copyOf(tags);
        vector = vector == null ? List.of() : List.copyOf(vector);
        understandingText = understandingText == null ? "" : understandingText.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        channelStatuses = channelStatuses == null ? java.util.Map.of() : java.util.Map.copyOf(channelStatuses);
    }
}
