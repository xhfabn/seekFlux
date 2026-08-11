package io.seekflux.interaction.domain;

public enum InteractionType {
    EXPOSURE(false),
    CLICK(true),
    PLAY_START(true),
    LIKE(true),
    SAVE(true),
    PLAY_COMPLETE(true),
    NOT_INTERESTED(true);

    private final boolean requiresExposure;

    InteractionType(boolean requiresExposure) {
        this.requiresExposure = requiresExposure;
    }

    public boolean requiresExposure() {
        return requiresExposure;
    }
}
