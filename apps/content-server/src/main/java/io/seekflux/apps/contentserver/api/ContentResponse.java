package io.seekflux.apps.contentserver.api;

import io.seekflux.content.domain.ContentProfile;
import io.seekflux.content.domain.ContentSource;
import io.seekflux.content.domain.ContentStatus;
import io.seekflux.content.domain.ContentType;
import io.seekflux.content.port.in.ContentView;
import java.time.Instant;
import java.util.List;

public record ContentResponse(
        String contentId,
        String creatorId,
        ContentType contentType,
        String mediaUri,
        List<String> assetUris,
        String title,
        String description,
        String body,
        List<String> sourceTags,
        SourceResponse source,
        ContentStatus status,
        ProfileResponse profile,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant withdrawnAt) {

    static ContentResponse from(ContentView view) {
        return new ContentResponse(
                view.id().toString(),
                view.creatorId(),
                view.contentType(),
                view.mediaUri(),
                view.assetUris(),
                view.title(),
                view.description(),
                view.body(),
                view.sourceTags(),
                SourceResponse.from(view.source()),
                view.status(),
                ProfileResponse.from(view.profile()),
                view.version(),
                view.createdAt(),
                view.updatedAt(),
                view.publishedAt(),
                view.withdrawnAt());
    }

    public record SourceResponse(
            String provider,
            String externalId,
            String sourcePageUri,
            String author,
            String licenseName) {

        static SourceResponse from(ContentSource source) {
            return new SourceResponse(source.provider(), source.externalId(), source.sourcePageUri(),
                    source.author(), source.licenseName());
        }
    }

    public record ProfileResponse(int version, String summary, List<String> tags, String transcript) {

        static ProfileResponse from(ContentProfile profile) {
            return profile == null
                    ? null
                    : new ProfileResponse(
                            profile.version(), profile.summary(), profile.tags(), profile.transcript());
        }
    }
}
