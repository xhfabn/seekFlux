package io.seekflux.apps.contentserver.api;

import io.seekflux.content.domain.ContentProfile;
import io.seekflux.content.domain.ContentStatus;
import io.seekflux.content.port.in.ContentView;
import java.time.Instant;
import java.util.List;

public record ContentResponse(
        String contentId,
        String creatorId,
        String mediaUri,
        String title,
        String description,
        List<String> sourceTags,
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
                view.mediaUri(),
                view.title(),
                view.description(),
                view.sourceTags(),
                view.status(),
                ProfileResponse.from(view.profile()),
                view.version(),
                view.createdAt(),
                view.updatedAt(),
                view.publishedAt(),
                view.withdrawnAt());
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
