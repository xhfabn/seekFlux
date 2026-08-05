package io.seekflux.content.domain;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class Content {

    private final ContentId id;
    private final String creatorId;
    private final String mediaUri;
    private final String title;
    private final String description;
    private final List<String> sourceTags;
    private final ContentStatus status;
    private final ContentProfile profile;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant publishedAt;
    private final Instant withdrawnAt;

    private Content(
            ContentId id,
            String creatorId,
            String mediaUri,
            String title,
            String description,
            List<String> sourceTags,
            ContentStatus status,
            ContentProfile profile,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant withdrawnAt) {
        this.id = Objects.requireNonNull(id, "content id must not be null");
        this.creatorId = requireText(creatorId, "creator id", 128);
        this.mediaUri = requireMediaUri(mediaUri);
        this.title = requireText(title, "title", 200);
        this.description = normalizeOptionalText(description, "description", 4_000);
        this.sourceTags = normalizeTags(sourceTags);
        this.status = Objects.requireNonNull(status, "content status must not be null");
        this.profile = profile;
        if (version < 0) {
            throw new IllegalArgumentException("aggregate version must not be negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        this.publishedAt = publishedAt;
        this.withdrawnAt = withdrawnAt;
        validateState();
    }

    public static Content submit(
            ContentId id,
            String creatorId,
            String mediaUri,
            String title,
            String description,
            List<String> sourceTags,
            Instant now) {
        return new Content(
                id,
                creatorId,
                mediaUri,
                title,
                description,
                sourceTags,
                ContentStatus.SUBMITTED,
                null,
                0,
                now,
                now,
                null,
                null);
    }

    public static Content restore(
            ContentId id,
            String creatorId,
            String mediaUri,
            String title,
            String description,
            List<String> sourceTags,
            ContentStatus status,
            ContentProfile profile,
            long version,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt,
            Instant withdrawnAt) {
        return new Content(
                id,
                creatorId,
                mediaUri,
                title,
                description,
                sourceTags,
                status,
                profile,
                version,
                createdAt,
                updatedAt,
                publishedAt,
                withdrawnAt);
    }

    public Content completeProfile(ContentProfile newProfile, Instant now) {
        Objects.requireNonNull(newProfile, "profile must not be null");
        Objects.requireNonNull(now, "completion time must not be null");
        if (status == ContentStatus.WITHDRAWN) {
            throw new ContentStateException("withdrawn content cannot receive a profile");
        }
        if (profile != null && newProfile.version() < profile.version()) {
            throw new ContentStateException("profile version cannot move backwards");
        }
        if (profile != null && newProfile.version() == profile.version()) {
            if (profile.equals(newProfile)) {
                return this;
            }
            throw new ContentStateException("profile version already exists with different content");
        }
        return copy(ContentStatus.PROFILE_READY, newProfile, version + 1, now, null, null);
    }

    public Content publish(Instant now) {
        Objects.requireNonNull(now, "publication time must not be null");
        if (status == ContentStatus.PUBLISHED) {
            return this;
        }
        if (status != ContentStatus.PROFILE_READY || profile == null) {
            throw new ContentStateException("content profile must be ready before publication");
        }
        return copy(ContentStatus.PUBLISHED, profile, version + 1, now, now, null);
    }

    public Content withdraw(Instant now) {
        Objects.requireNonNull(now, "withdrawal time must not be null");
        if (status == ContentStatus.WITHDRAWN) {
            return this;
        }
        return copy(ContentStatus.WITHDRAWN, profile, version + 1, now, publishedAt, now);
    }

    private Content copy(
            ContentStatus newStatus,
            ContentProfile newProfile,
            long newVersion,
            Instant newUpdatedAt,
            Instant newPublishedAt,
            Instant newWithdrawnAt) {
        return new Content(
                id,
                creatorId,
                mediaUri,
                title,
                description,
                sourceTags,
                newStatus,
                newProfile,
                newVersion,
                createdAt,
                newUpdatedAt,
                newPublishedAt,
                newWithdrawnAt);
    }

    private void validateState() {
        if ((status == ContentStatus.PROFILE_READY || status == ContentStatus.PUBLISHED) && profile == null) {
            throw new IllegalArgumentException(status + " content must have a profile");
        }
        if (status == ContentStatus.PUBLISHED && publishedAt == null) {
            throw new IllegalArgumentException("published content must have a publication time");
        }
        if (status == ContentStatus.WITHDRAWN && withdrawnAt == null) {
            throw new IllegalArgumentException("withdrawn content must have a withdrawal time");
        }
    }

    private static String requireMediaUri(String value) {
        String normalized = requireText(value, "media URI", 2_048);
        URI uri;
        try {
            uri = URI.create(normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("media URI is invalid", exception);
        }
        if (uri.getScheme() == null || uri.getScheme().isBlank()) {
            throw new IllegalArgumentException("media URI must be absolute");
        }
        return normalized;
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
                throw new IllegalArgumentException("source tag must not exceed 64 characters");
            }
            normalized.add(value);
        }
        if (normalized.size() > 50) {
            throw new IllegalArgumentException("content must not contain more than 50 source tags");
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

    private static String normalizeOptionalText(String value, String field, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    public ContentId id() {
        return id;
    }

    public String creatorId() {
        return creatorId;
    }

    public String mediaUri() {
        return mediaUri;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public List<String> sourceTags() {
        return sourceTags;
    }

    public ContentStatus status() {
        return status;
    }

    public ContentProfile profile() {
        return profile;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public Instant withdrawnAt() {
        return withdrawnAt;
    }
}
