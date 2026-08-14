package io.seekflux.search.port.out;

import java.util.List;

public record MediaSearchCandidate(
        String contentId,
        String contentType,
        String mediaUri,
        List<String> assetUris,
        String title,
        String summary,
        List<String> tags,
        long startMillis,
        long endMillis,
        String previewUri,
        double score,
        String modelVersion) {
}
