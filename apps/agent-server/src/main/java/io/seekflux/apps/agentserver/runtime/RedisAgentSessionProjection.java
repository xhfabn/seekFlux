package io.seekflux.apps.agentserver.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.agent.port.in.AgentSearchResult;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisAgentSessionProjection {

    private static final String KEY_PREFIX = "seekflux:agent:session:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public RedisAgentSessionProjection(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
    }

    public void project(AgentSearchResult result) {
        try {
            redis.opsForValue().set(
                    KEY_PREFIX + result.sessionId(),
                    objectMapper.writeValueAsString(result),
                    ttl);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to serialize agent session projection", error);
        } catch (RuntimeException ignored) {
            // PostgreSQL workspace events remain authoritative when the hot projection is unavailable.
        }
    }
}
