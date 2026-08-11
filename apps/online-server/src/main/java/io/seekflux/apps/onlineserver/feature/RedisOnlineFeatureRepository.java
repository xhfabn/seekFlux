package io.seekflux.apps.onlineserver.feature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureKeys;
import io.seekflux.feature.domain.FeatureTopicScore;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.out.OnlineFeatureRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public final class RedisOnlineFeatureRepository implements OnlineFeatureRepository {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisOnlineFeatureRepository(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ShortTermInterestSnapshot> findShortTermInterest(String userId) {
        return Optional.ofNullable(redis.opsForValue().get(FeatureKeys.shortInterest(userId)))
                .map(json -> decodeInterest(userId, json));
    }

    @Override
    public Optional<ContentHeatSnapshot> findContentHeat(UUID contentId) {
        return Optional.ofNullable(redis.opsForValue().get(FeatureKeys.contentHeat(contentId)))
                .map(json -> decodeHeat(contentId, json));
    }

    private ShortTermInterestSnapshot decodeInterest(String userId, String json) {
        JsonNode node = parse(json);
        List<FeatureTopicScore> topics = new ArrayList<>();
        node.path("topics").forEach(topic -> topics.add(new FeatureTopicScore(
                required(topic, "topic"), topic.path("score").asDouble(Double.NaN))));
        return new ShortTermInterestSnapshot(
                userId,
                topics,
                Instant.parse(required(node, "window_start")),
                Instant.parse(required(node, "window_end")),
                Instant.parse(required(node, "computed_at")),
                required(node, "feature_version"));
    }

    private ContentHeatSnapshot decodeHeat(UUID contentId, String json) {
        JsonNode node = parse(json);
        return new ContentHeatSnapshot(
                contentId,
                node.path("score").asDouble(Double.NaN),
                node.path("event_count").asLong(-1),
                Instant.parse(required(node, "window_start")),
                Instant.parse(required(node, "window_end")),
                Instant.parse(required(node, "computed_at")),
                required(node, "feature_version"));
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("online realtime feature snapshot is invalid", invalid);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("online realtime feature is missing " + field);
        }
        return value;
    }
}
