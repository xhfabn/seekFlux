package io.seekflux.platform.agentruntime.llm;

import java.util.Optional;

public interface ShadowSettingsStore {

    ShadowSettingsStore NOOP = new ShadowSettingsStore() {
        @Override
        public Optional<ShadowControl.Settings> load() {
            return Optional.empty();
        }

        @Override
        public void save(ShadowControl.Settings settings) {
        }
    };

    Optional<ShadowControl.Settings> load();

    void save(ShadowControl.Settings settings);
}
