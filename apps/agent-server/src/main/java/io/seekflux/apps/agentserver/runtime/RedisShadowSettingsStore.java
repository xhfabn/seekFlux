package io.seekflux.apps.agentserver.runtime;

import io.seekflux.platform.agentruntime.llm.ShadowControl;
import io.seekflux.platform.agentruntime.llm.ShadowSettingsStore;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisShadowSettingsStore implements ShadowSettingsStore {

    private static final String KEY = "seekflux:agent:shadow:settings";

    private final StringRedisTemplate redis;

    public RedisShadowSettingsStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<ShadowControl.Settings> load() {
        String value = redis.opsForValue().get(KEY);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String[] parts = value.split("\\|", -1);
        if (parts.length != 2) {
            return Optional.empty();
        }
        try {
            return Optional.of(new ShadowControl.Settings(
                    Boolean.parseBoolean(parts[0]),
                    Double.parseDouble(parts[1])));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    @Override
    public void save(ShadowControl.Settings settings) {
        redis.opsForValue().set(KEY, settings.enabled() + "|" + settings.sampleRate());
    }
}
