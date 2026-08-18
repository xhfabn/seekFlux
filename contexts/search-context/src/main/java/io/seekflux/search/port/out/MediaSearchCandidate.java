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
        String modelVersion,
        List<String> matchedChannels,
        List<MediaUnderstandingEvidence> evidence) {

    public MediaSearchCandidate {
        assetUris = assetUris == null ? List.of() : List.copyOf(assetUris);
        tags = tags == null ? List.of() : List.copyOf(tags);
        matchedChannels = matchedChannels == null ? List.of() : List.copyOf(matchedChannels);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public MediaSearchCandidate withFusion(double fusionScore, List<String> channels) {
        return new MediaSearchCandidate(contentId, contentType, mediaUri, assetUris, title, summary, tags,
                startMillis, endMillis, previewUri, fusionScore, modelVersion, channels, evidence);
    }
}
