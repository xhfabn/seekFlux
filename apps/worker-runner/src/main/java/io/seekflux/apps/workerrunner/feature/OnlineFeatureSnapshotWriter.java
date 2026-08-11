package io.seekflux.apps.workerrunner.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.feature.application.RealtimeFeaturePolicy;
import io.seekflux.feature.application.FeatureTopics;
import io.seekflux.feature.domain.FeatureKeys;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public final class OnlineFeatureSnapshotWriter {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public OnlineFeatureSnapshotWriter(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = FeatureTopics.SNAPSHOT_UPDATED,
            groupId = "seekflux-online-feature-writer-v1")
    public void consume(String envelopeJson) throws Exception {
        JsonNode envelope = objectMapper.readTree(envelopeJson);
        JsonNode payload = envelope.path("payload");
        String type = required(payload, "snapshot_type");
        String entityId = required(payload, "entity_id");
        String key = switch (type) {
            case "SHORT_TERM_INTEREST" -> FeatureKeys.shortInterest(entityId);
            case "CONTENT_HEAT" -> FeatureKeys.contentHeat(UUID.fromString(entityId));
            default -> throw new IllegalArgumentException("unsupported feature snapshot type: " + type);
        };
        Duration ttl = RealtimeFeaturePolicy.ONLINE_TTL;
        redis.opsForValue().set(key, payload.toString(), ttl);
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("feature snapshot is missing " + field);
        }
        return value;
    }
}
