package io.seekflux.feature.domain;

import java.util.Locale;

public record FeatureTopicScore(String topic, double score) {

    public FeatureTopicScore {
        topic = topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT);
        if (topic.isEmpty() || topic.length() > 64) {
            throw new IllegalArgumentException("feature topic must contain between 1 and 64 characters");
        }
        if (!Double.isFinite(score) || score <= 0) {
            throw new IllegalArgumentException("feature topic score must be finite and positive");
        }
    }
}
