package io.seekflux.platform.agentruntime.application.spi.capability.shadow;

import io.seekflux.platform.agentruntime.application.spi.capability.shadow.model.ShadowSettings;
import java.util.Optional;

public interface ShadowSettingsStore {

    ShadowSettingsStore NOOP = new ShadowSettingsStore() {
        @Override
        public Optional<ShadowSettings> load() {
            return Optional.empty();
        }

        @Override
        public void save(ShadowSettings settings) {
        }
    };

    Optional<ShadowSettings> load();

    void save(ShadowSettings settings);
}
