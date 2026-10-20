package io.seekflux.core.content.api;

import io.seekflux.core.content.domain.Content;
import io.seekflux.core.content.domain.ContentId;
import io.seekflux.core.content.domain.ContentProfile;
import io.seekflux.core.content.domain.ContentSource;
import io.seekflux.core.content.domain.ContentStatus;
import io.seekflux.core.content.domain.ContentType;
import java.time.Instant;
import java.util.List;

public record ContentView(
        ContentId id,
        String creatorId,
        ContentType contentType,
        String mediaUri,
        List<String> assetUris,
        String title,
        String description,
        String body,
        List<String> sourceTags,
        ContentSource source,
        ContentStatus status,
        ContentProfile profile,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant withdrawnAt) {

    public static ContentView from(Content content) {
        return new ContentView(
                content.id(),
                content.creatorId(),
                content.contentType(),
                content.mediaUri(),
                content.assetUris(),
                content.title(),
                content.description(),
                content.body(),
                content.sourceTags(),
                content.source(),
                content.status(),
                content.profile(),
                content.version(),
                content.createdAt(),
                content.updatedAt(),
                content.publishedAt(),
                content.withdrawnAt());
    }
}
