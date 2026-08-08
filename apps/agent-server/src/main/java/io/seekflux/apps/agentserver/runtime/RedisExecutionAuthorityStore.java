package io.seekflux.apps.agentserver.runtime;

import io.seekflux.platform.agentruntime.execution.ExecutionAuthority;
import io.seekflux.platform.agentruntime.execution.ExecutionAuthorityStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public final class RedisExecutionAuthorityStore implements ExecutionAuthorityStore {

    private static final String KEY_PREFIX = "seekflux:agent:running:";
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisExecutionAuthorityStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<ExecutionAuthority> acquire(String sessionId, String ownerToken, long ttlMillis) {
        String key = KEY_PREFIX + sessionId;
        Boolean acquired = redis.opsForValue().setIfAbsent(key, ownerToken, Duration.ofMillis(ttlMillis));
        return Boolean.TRUE.equals(acquired)
                ? Optional.of(new RedisAuthority(key, ownerToken))
                : Optional.empty();
    }

    @Override
    public boolean isHeld(String sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + sessionId));
        } catch (RuntimeException unavailable) {
            return true;
        }
    }

    private final class RedisAuthority implements ExecutionAuthority {
        private final String key;
        private final String ownerToken;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RedisAuthority(String key, String ownerToken) {
            this.key = key;
            this.ownerToken = ownerToken;
        }

        @Override
        public boolean renew(long ttlMillis) {
            if (closed.get()) {
                return false;
            }
            try {
                Long renewed = redis.execute(
                        RENEW,
                        List.of(key),
                        ownerToken,
                        Long.toString(ttlMillis));
                return renewed != null && renewed == 1;
            } catch (RuntimeException unavailable) {
                return false;
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                redis.execute(RELEASE, List.of(key), ownerToken);
            } catch (RuntimeException ignored) {
                // TTL remains the final safety net if Redis is unavailable during release.
            }
        }
    }
}
