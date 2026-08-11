package io.seekflux.pipelines.realtimefeatures;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.interaction.domain.InteractionType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.flink.api.common.functions.MapFunction;

public final class InteractionEnvelopeMapper implements MapFunction<String, RealtimeFeatureEvent> {

    private transient ObjectMapper objectMapper;

    @Override
    public RealtimeFeatureEvent map(String envelopeJson) throws Exception {
        if (objectMapper == null) {
            objectMapper = new ObjectMapper();
        }
        JsonNode envelope = objectMapper.readTree(envelopeJson);
        JsonNode payload = envelope.path("payload");
        List<String> tags = new ArrayList<>();
        payload.path("content_tags").forEach(tag -> {
            if (tag.isTextual() && !tag.asText().isBlank()) {
                tags.add(tag.asText());
            }
        });
        return new RealtimeFeatureEvent(
                UUID.fromString(required(envelope, "event_id")),
                required(payload, "user_id"),
                InteractionType.valueOf(required(payload, "event_type")),
                UUID.fromString(required(payload, "content_id")),
                tags,
                Instant.parse(required(payload, "event_time")),
                Instant.parse(required(envelope, "ingested_at")));
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Flink interaction event is missing " + field);
        }
        return value;
    }
}
