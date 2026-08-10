package io.seekflux.platform.agentruntime.llm;

import java.util.concurrent.atomic.AtomicReference;

public final class ShadowControl {

    private final AtomicReference<Settings> settings;
    private final ShadowSettingsStore settingsStore;

    public ShadowControl(boolean enabled, double sampleRate) {
        this(enabled, sampleRate, ShadowSettingsStore.NOOP);
    }

    public ShadowControl(boolean enabled, double sampleRate, ShadowSettingsStore settingsStore) {
        settings = new AtomicReference<>(new Settings(enabled, sampleRate));
        this.settingsStore = settingsStore;
    }

    public Settings current() {
        return refresh();
    }

    public Settings update(boolean enabled, double sampleRate) {
        Settings updated = new Settings(enabled, sampleRate);
        settingsStore.save(updated);
        settings.set(updated);
        return updated;
    }

    public boolean shouldSample(String requestId) {
        Settings current = refresh();
        if (!current.enabled() || current.sampleRate() <= 0) {
            return false;
        }
        long bucket = Integer.toUnsignedLong(requestId.hashCode()) % 10_000;
        return bucket < Math.round(current.sampleRate() * 10_000);
    }

    private Settings refresh() {
        try {
            settingsStore.load().ifPresent(settings::set);
        } catch (RuntimeException ignored) {
            // Shadow control must never make the primary path unavailable.
        }
        return settings.get();
    }

    public record Settings(boolean enabled, double sampleRate) {
        public Settings {
            if (sampleRate < 0 || sampleRate > 1) {
                throw new IllegalArgumentException("shadow sample rate must be between 0 and 1");
            }
        }
    }
}
