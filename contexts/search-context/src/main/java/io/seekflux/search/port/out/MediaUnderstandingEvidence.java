package io.seekflux.search.port.out;

public record MediaUnderstandingEvidence(
        String channel,
        String text,
        double confidence,
        long startMillis,
        long endMillis,
        String modelVersion) {

    public MediaUnderstandingEvidence {
        channel = required(channel, "channel");
        text = required(text, "text");
        modelVersion = required(modelVersion, "modelVersion");
        if (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("understanding confidence must be between 0 and 1");
        }
        if (startMillis < 0 || endMillis < startMillis) {
            throw new IllegalArgumentException("invalid understanding evidence range");
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
