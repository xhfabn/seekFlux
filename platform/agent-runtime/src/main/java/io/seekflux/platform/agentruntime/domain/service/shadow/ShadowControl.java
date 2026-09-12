package io.seekflux.platform.agentruntime.domain.service.shadow;

import io.seekflux.platform.agentruntime.application.spi.capability.shadow.ShadowSettingsStore;
import io.seekflux.platform.agentruntime.application.spi.capability.shadow.model.ShadowSettings;
import java.util.concurrent.atomic.AtomicReference;

public final class ShadowControl {

    private final AtomicReference<ShadowSettings> settings;
    private final ShadowSettingsStore settingsStore;

    public ShadowControl(boolean enabled, double sampleRate) {
        this(enabled, sampleRate, ShadowSettingsStore.NOOP);
    }

    public ShadowControl(boolean enabled, double sampleRate, ShadowSettingsStore settingsStore) {
        settings = new AtomicReference<>(new ShadowSettings(enabled, sampleRate));
        this.settingsStore = settingsStore;
    }

    public ShadowSettings current() {
        return refresh();
    }

    public ShadowSettings update(boolean enabled, double sampleRate) {
        ShadowSettings updated = new ShadowSettings(enabled, sampleRate);
        settingsStore.save(updated);
        settings.set(updated);
        return updated;
    }

    public boolean shouldSample(String requestId) {
        ShadowSettings current = refresh();
        if (!current.enabled() || current.sampleRate() <= 0) {
            return false;
        }
        long bucket = Integer.toUnsignedLong(requestId.hashCode()) % 10_000;
        return bucket < Math.round(current.sampleRate() * 10_000);
    }

    private ShadowSettings refresh() {
        try {
            settingsStore.load().ifPresent(settings::set);
        } catch (RuntimeException ignored) {
            // Shadow control must never make the primary path unavailable.
        }
        return settings.get();
    }

}
