package io.seekflux.apps.agentserver.runtime;

import io.seekflux.platform.agentruntime.execution.CancellationSignalStore;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisCancellationSignalStore implements CancellationSignalStore {

    private static final String KEY_PREFIX = "seekflux:agent:interrupt:";
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisCancellationSignalStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public CancelSignal poll(String sessionId, Instant taskStartedAt) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + sessionId);
            if (value == null || value.isBlank()) {
                return CancelSignal.NONE;
            }
            String[] parts = value.split("\\|", 2);
            Instant signalTime = Instant.parse(parts[0]);
            if (!signalTime.isAfter(taskStartedAt)) {
                return CancelSignal.NONE;
            }
            boolean steer = parts.length == 2 && "steer".equals(parts[1]);
            return new CancelSignal(true, steer);
        } catch (RuntimeException unavailableOrMalformed) {
            return CancelSignal.NONE;
        }
    }

    @Override
    public boolean write(String sessionId, boolean steer, Instant signalTime) {
        try {
            String value = signalTime + (steer ? "|steer" : "");
            redis.opsForValue().set(KEY_PREFIX + sessionId, value, ttl);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
