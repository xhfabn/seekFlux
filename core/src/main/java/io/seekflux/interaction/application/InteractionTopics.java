package io.seekflux.interaction.application;

import io.seekflux.interaction.domain.InteractionType;

public final class InteractionTopics {

    public static final String EXPOSURE = "interaction.exposure.v1";
    public static final String CLICK = "interaction.click.v1";
    public static final String PLAY_START = "interaction.play-start.v1";
    public static final String LIKE = "interaction.like.v1";
    public static final String SAVE = "interaction.save.v1";
    public static final String PLAY_COMPLETE = "interaction.play-complete.v1";
    public static final String NOT_INTERESTED = "interaction.not-interested.v1";

    private InteractionTopics() {
    }

    public static String forType(InteractionType type) {
        return switch (type) {
            case EXPOSURE -> EXPOSURE;
            case CLICK -> CLICK;
            case PLAY_START -> PLAY_START;
            case LIKE -> LIKE;
            case SAVE -> SAVE;
            case PLAY_COMPLETE -> PLAY_COMPLETE;
            case NOT_INTERESTED -> NOT_INTERESTED;
        };
    }
}
