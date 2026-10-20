package io.seekflux.feature.domain;

import java.util.Objects;
import java.util.Optional;

public record FeatureRead<T>(FeatureReadStatus status, Optional<T> value) {

    public FeatureRead {
        Objects.requireNonNull(status, "status must not be null");
        value = value == null ? Optional.empty() : value;
        if (status == FeatureReadStatus.FRESH && value.isEmpty()) {
            throw new IllegalArgumentException("fresh feature read must contain a value");
        }
        if (status != FeatureReadStatus.FRESH && value.isPresent()) {
            throw new IllegalArgumentException("non-fresh feature read must not expose a value");
        }
    }

    public static <T> FeatureRead<T> fresh(T value) {
        return new FeatureRead<>(FeatureReadStatus.FRESH, Optional.of(value));
    }

    public static <T> FeatureRead<T> empty(FeatureReadStatus status) {
        return new FeatureRead<>(status, Optional.empty());
    }
}
