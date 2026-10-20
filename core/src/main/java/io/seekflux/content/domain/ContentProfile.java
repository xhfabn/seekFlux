package io.seekflux.content.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record ContentProfile(int version, String summary, List<String> tags, String transcript) {

    public ContentProfile {
        if (version < 1) {
            throw new IllegalArgumentException("profile version must be positive");
        }
        summary = requireText(summary, "profile summary", 4_000);
        tags = normalizeTags(tags);
        transcript = normalizeOptionalText(transcript, 50_000);
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        var normalized = new LinkedHashSet<String>();
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            String value = tag.trim();
            if (value.length() > 64) {
                throw new IllegalArgumentException("profile tag must not exceed 64 characters");
            }
            normalized.add(value);
        }
        if (normalized.size() > 50) {
            throw new IllegalArgumentException("profile must not contain more than 50 tags");
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

    private static String normalizeOptionalText(String value, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("profile transcript must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
