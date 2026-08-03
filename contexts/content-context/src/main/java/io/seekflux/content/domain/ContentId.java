package io.seekflux.content.domain;

import java.util.Objects;
import java.util.UUID;

public record ContentId(UUID value) {

    public ContentId {
        Objects.requireNonNull(value, "content id must not be null");
    }

    public static ContentId random() {
        return new ContentId(UUID.randomUUID());
    }

    public static ContentId parse(String value) {
        return new ContentId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
