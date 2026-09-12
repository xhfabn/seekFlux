package io.seekflux.platform.agentruntime.application.spi.capability.shadow.model;

public record ShadowSettings(boolean enabled, double sampleRate) {

    public ShadowSettings {
        if (sampleRate < 0 || sampleRate > 1) {
            throw new IllegalArgumentException("shadow sample rate must be between 0 and 1");
        }
    }
}
