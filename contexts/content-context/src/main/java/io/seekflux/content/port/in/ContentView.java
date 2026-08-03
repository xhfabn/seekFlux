package io.seekflux.content.port.in;

import io.seekflux.content.domain.Content;
import io.seekflux.content.domain.ContentId;
import io.seekflux.content.domain.ContentProfile;
import io.seekflux.content.domain.ContentStatus;
import java.time.Instant;
import java.util.List;

public record ContentView(
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

    public static ContentView from(Content content) {
        return new ContentView(
                content.id(),
                content.creatorId(),
                content.mediaUri(),
                content.title(),
                content.description(),
                content.sourceTags(),
                content.status(),
                content.profile(),
                content.version(),
                content.createdAt(),
                content.updatedAt(),
                content.publishedAt(),
                content.withdrawnAt());
    }
}
