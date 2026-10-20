package io.seekflux.content.domain;

import java.net.URI;

public record ContentSource(
        String provider,
        String externalId,
        String sourcePageUri,
        String author,
        String licenseName) {

    public ContentSource {
        provider = optionalText(provider, "source provider", 64);
        externalId = optionalText(externalId, "external id", 256);
        sourcePageUri = optionalUri(sourcePageUri);
        author = optionalText(author, "source author", 128);
        licenseName = optionalText(licenseName, "license name", 128);
        if (provider.isEmpty() != externalId.isEmpty()) {
            throw new IllegalArgumentException("source provider and external id must be supplied together");
        }
    }

    public static ContentSource manual() {
        return new ContentSource("", "", "", "", "");
    }

    public boolean imported() {
        return !provider.isEmpty();
    }

    private static String optionalText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static String optionalUri(String value) {
        String normalized = optionalText(value, "source page URI", 2_048);
        if (normalized.isEmpty()) {
            return normalized;
        }
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("source page URI is invalid", exception);
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) {
            throw new IllegalArgumentException("source page URI must be absolute");
        }
        return normalized;
    }
}
