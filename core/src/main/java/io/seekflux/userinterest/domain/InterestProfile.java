package io.seekflux.userinterest.domain;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record InterestProfile(String userId, List<String> topics, Instant updatedAt) {

    public InterestProfile {
        userId = requireText(userId, "user id", 128);
        topics = normalizeTopics(topics);
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    private static List<String> normalizeTopics(List<String> topics) {
        if (topics == null) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (String topic : topics) {
            if (topic == null || topic.isBlank()) {
                continue;
            }
            String value = topic.trim().toLowerCase(Locale.ROOT);
            if (value.length() > 64) {
                throw new IllegalArgumentException("interest topic must not exceed 64 characters");
            }
            normalized.add(value);
        }
        if (normalized.size() > 20) {
            throw new IllegalArgumentException("interest profile must not contain more than 20 topics");
        }
        return List.copyOf(normalized);
    }

    private static String requireText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
