package io.seekflux.apps.onlineserver.interest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.out.UserInterestRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public final class RedisUserInterestRepository implements UserInterestRepository {

    private static final String KEY_PREFIX = "seekflux:user-interest:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisUserInterestRepository(
            StringRedisTemplate redis,
            ObjectMapper objectMapper) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public Optional<InterestProfile> findByUserId(String userId) {
        return Optional.ofNullable(redis.opsForValue().get(key(userId)))
                .map(json -> decode(userId, json));
    }

    @Override
    public void save(InterestProfile profile) {
        try {
            String json = objectMapper.writeValueAsString(
                    new StoredProfile(profile.topics(), profile.updatedAt()));
            redis.opsForValue().set(key(profile.userId()), json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize user interest profile", exception);
        }
    }

    private InterestProfile decode(String userId, String json) {
        try {
            StoredProfile stored = objectMapper.readValue(json, StoredProfile.class);
            return new InterestProfile(userId, stored.topics(), stored.updatedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored user interest profile is invalid", exception);
        }
    }

    private static String key(String userId) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(userId.trim().getBytes(StandardCharsets.UTF_8));
        return KEY_PREFIX + encoded;
    }

    private record StoredProfile(List<String> topics, Instant updatedAt) {
    }
}
