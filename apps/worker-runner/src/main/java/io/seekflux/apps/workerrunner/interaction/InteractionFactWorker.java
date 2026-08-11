package io.seekflux.apps.workerrunner.interaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.interaction.application.InteractionTopics;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
public final class InteractionFactWorker {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public InteractionFactWorker(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
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
            groupId = "seekflux-interaction-facts-v1")
    public void consume(String envelope, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        JsonNode root = parse(envelope);
        JsonNode payload = root.path("payload");
        String eventType = required(payload, "event_type");
        if (!topic.equals(root.path("event_type").asText())) {
            throw new IllegalArgumentException("interaction topic does not match envelope event type");
        }
        jdbcClient.sql("""
                        INSERT INTO interaction.facts (
                            event_id, user_id, event_type, request_id, trace_id,
                            content_id, position, surface, event_time, ingested_at, payload
                        ) VALUES (
                            :eventId, :userId, :eventType, :requestId, :traceId,
                            :contentId, :position, :surface,
                            CAST(:eventTime AS timestamptz), CAST(:ingestedAt AS timestamptz),
                            CAST(:payload AS jsonb)
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", UUID.fromString(required(root, "event_id")))
                .param("userId", required(payload, "user_id"))
                .param("eventType", eventType)
                .param("requestId", required(payload, "request_id"))
                .param("traceId", required(payload, "trace_id"))
                .param("contentId", UUID.fromString(required(payload, "content_id")))
                .param("position", payload.path("position").asInt())
                .param("surface", required(payload, "surface"))
                .param("eventTime", required(payload, "event_time"))
                .param("ingestedAt", required(root, "ingested_at"))
                .param("payload", payload.toString())
                .update();
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("invalid interaction envelope", invalid);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("interaction event is missing " + field);
        }
        return value;
    }
}
