package io.seekflux.platform.agentruntime.infrastructure.redis;

import io.seekflux.platform.agentruntime.application.spi.capability.execution.CancellationSignalStore;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import java.util.List;

public final class RedisCancellationSignalStore implements CancellationSignalStore {

    private static final String KEY_PREFIX = "seekflux:agent:interrupt:";
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE =
            new DefaultRedisScript<>(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then "
                            + "return redis.call('del', KEYS[1]) else return 0 end",
                    Long.class);
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

    @Override
    public boolean clearSteerThrough(String sessionId, Instant signalTime) {
        String key = KEY_PREFIX + sessionId;
        try {
            String value = redis.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return false;
            }
            String[] parts = value.split("\\|", 2);
            Instant storedAt = Instant.parse(parts[0]);
            CancellationCause cause = parts.length == 1
                    ? CancellationCause.USER_CANCEL
                    : parseCause(parts[1]);
            if (cause != CancellationCause.STEER || storedAt.isAfter(signalTime)) {
                return false;
            }
            Long removed = redis.execute(COMPARE_AND_DELETE, List.of(key), value);
            return removed != null && removed == 1L;
        } catch (RuntimeException unavailableOrMalformed) {
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
