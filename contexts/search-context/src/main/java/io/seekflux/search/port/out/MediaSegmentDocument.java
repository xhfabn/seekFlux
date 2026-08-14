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
        Instant publishedAt) {
}
