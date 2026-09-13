package io.seekflux.platform.agentruntime.infrastructure.redis;

import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
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
            CancellationCause cause = parts.length == 1
                    ? CancellationCause.USER_CANCEL
                    : parseCause(parts[1]);
            return new CancelSignal(true, cause);
        } catch (RuntimeException unavailableOrMalformed) {
            return CancelSignal.NONE;
        }
    }

    @Override
    public boolean write(String sessionId, boolean steer, Instant signalTime) {
        return write(sessionId, steer ? CancellationCause.STEER : CancellationCause.USER_CANCEL, signalTime);
    }

    @Override
    public boolean write(String sessionId, CancellationCause cause, Instant signalTime) {
        try {
            String value = signalTime + "|" + cause.name();
            redis.opsForValue().set(KEY_PREFIX + sessionId, value, ttl);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private static CancellationCause parseCause(String value) {
        if ("steer".equalsIgnoreCase(value)) {
            return CancellationCause.STEER;
        }
        try {
            return CancellationCause.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException malformed) {
            return CancellationCause.USER_CANCEL;
        }
    }
}
