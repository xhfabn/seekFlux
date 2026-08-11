package io.seekflux.apps.workerrunner.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.feature.port.in.RealtimeFeatureProjectionUseCase;
import io.seekflux.interaction.application.InteractionTopics;
import io.seekflux.interaction.domain.InteractionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public final class RealtimeFeatureProjectionWorker {

    private final RealtimeFeatureProjectionUseCase projector;
    private final ObjectMapper objectMapper;

    public RealtimeFeatureProjectionWorker(
            RealtimeFeatureProjectionUseCase projector,
            ObjectMapper objectMapper) {
        this.projector = projector;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = {
                    InteractionTopics.EXPOSURE,
                    InteractionTopics.CLICK,
                    InteractionTopics.PLAY_START,
                    InteractionTopics.LIKE,
                    InteractionTopics.SAVE,
                    InteractionTopics.PLAY_COMPLETE,
                    InteractionTopics.NOT_INTERESTED
            },
            groupId = "seekflux-realtime-feature-reference-v1")
    public void consume(String envelopeJson) throws Exception {
        JsonNode envelope = objectMapper.readTree(envelopeJson);
        JsonNode payload = envelope.path("payload");
        List<String> tags = new ArrayList<>();
        payload.path("content_tags").forEach(tag -> {
            if (tag.isTextual() && !tag.asText().isBlank()) {
                tags.add(tag.asText());
            }
        });
        projector.project(new RealtimeFeatureEvent(
                UUID.fromString(required(envelope, "event_id")),
                required(payload, "user_id"),
                InteractionType.valueOf(required(payload, "event_type")),
                UUID.fromString(required(payload, "content_id")),
                tags,
                Instant.parse(required(payload, "event_time")),
                Instant.parse(required(envelope, "ingested_at"))));
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("realtime feature event is missing " + field);
        }
        return value;
    }
}
