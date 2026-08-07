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
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public final class RedisUserInterestRepository implements UserInterestRepository {

    private static final String KEY_PREFIX = "seekflux:user-interest:";

    private final ReactiveStringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public RedisUserInterestRepository(
            ReactiveStringRedisTemplate redis,
            ObjectMapper objectMapper) {
        this.redis = Objects.requireNonNull(redis, "redis must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public Mono<InterestProfile> findByUserId(String userId) {
        return redis.opsForValue().get(key(userId)).map(json -> decode(userId, json));
    }

    @Override
    public Mono<Void> save(InterestProfile profile) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(
                        new StoredProfile(profile.topics(), profile.updatedAt())))
                .flatMap(json -> redis.opsForValue().set(key(profile.userId()), json))
                .then();
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
