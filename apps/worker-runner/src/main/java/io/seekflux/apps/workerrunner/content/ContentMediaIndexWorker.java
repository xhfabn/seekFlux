package io.seekflux.apps.workerrunner.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.content.application.ContentApplicationService;
import io.seekflux.search.port.out.MediaEmbeddingPort;
import io.seekflux.search.port.out.MediaModality;
import io.seekflux.search.port.out.MediaSegmentDocument;
import io.seekflux.search.port.out.MediaSegmentIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "seekflux.multimodal", name = "enabled", havingValue = "true")
public final class ContentMediaIndexWorker {

    private final MediaEmbeddingPort embeddingPort;
    private final MediaSegmentIndex segmentIndex;
    private final ObjectMapper objectMapper;
    private final int maxVideoSegments;

    public ContentMediaIndexWorker(
            MediaEmbeddingPort embeddingPort,
            MediaSegmentIndex segmentIndex,
            ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value("${seekflux.multimodal.max-video-segments:12}")
                    int maxVideoSegments) {
        this.embeddingPort = embeddingPort;
        this.segmentIndex = segmentIndex;
        this.objectMapper = objectMapper;
        this.maxVideoSegments = maxVideoSegments;
    }

    @KafkaListener(
            topics = ContentApplicationService.CONTENT_PROFILE_PUBLISHED,
            groupId = "seekflux-content-media-index-v1")
    public void indexPublished(String envelopeJson) throws Exception {
        JsonNode envelope = objectMapper.readTree(envelopeJson);
        JsonNode payload = envelope.path("payload");
        String contentId = requiredText(payload, "content_id");
        String contentType = requiredText(payload, "content_type");
        List<String> assets = new ArrayList<>();
        payload.path("asset_uris").forEach(value -> assets.add(value.asText()));
        List<String> tags = new ArrayList<>();
        payload.path("tags").forEach(value -> tags.add(value.asText()));
        MediaModality modality = "VIDEO".equals(contentType) ? MediaModality.VIDEO : MediaModality.IMAGE;

        segmentIndex.deleteByContentId(contentId);
        for (int assetIndex = 0; assetIndex < assets.size(); assetIndex++) {
            String assetUri = assets.get(assetIndex);
            var batch = embeddingPort.embed(modality, assetUri,
                    modality == MediaModality.VIDEO ? maxVideoSegments : 1);
            for (var segment : batch.segments()) {
                segmentIndex.upsert(new MediaSegmentDocument(
                        contentId, contentType, requiredText(payload, "media_uri"), assets,
                        requiredText(payload, "title"), requiredText(payload, "summary"), tags,
                        assetIndex * 1000 + segment.ordinal(), segment.startMillis(), segment.endMillis(),
                        segment.previewUri().isBlank() ? assetUri : segment.previewUri(),
                        batch.modelVersion(), segment.vector(), Instant.parse(requiredText(envelope, "event_time"))));
            }
        }
    }

    @KafkaListener(
            topics = ContentApplicationService.CONTENT_DISTRIBUTION_CHANGED,
            groupId = "seekflux-content-media-index-v1")
    public void removeWithdrawn(String envelopeJson) throws Exception {
        JsonNode payload = objectMapper.readTree(envelopeJson).path("payload");
        if ("WITHDRAWN".equals(payload.path("distribution_status").asText())) {
            segmentIndex.deleteByContentId(requiredText(payload, "content_id"));
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("missing media index event field: " + field);
        return value;
    }
}
