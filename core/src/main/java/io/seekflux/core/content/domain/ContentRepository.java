package io.seekflux.core.content.domain;

import io.seekflux.core.content.domain.Content;
import io.seekflux.core.content.domain.ContentId;
import java.util.Optional;

public interface ContentRepository {

    Optional<Content> findById(ContentId contentId);

    default Optional<Content> findBySource(String provider, String externalId) {
        return Optional.empty();
    }

    void insert(Content content, ContentEvent event);

    boolean update(Content content, long expectedVersion, ContentEvent event);
}
