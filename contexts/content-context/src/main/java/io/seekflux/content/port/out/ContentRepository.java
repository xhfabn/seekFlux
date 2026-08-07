package io.seekflux.content.port.out;

import io.seekflux.content.domain.Content;
import io.seekflux.content.domain.ContentId;
import java.util.Optional;

public interface ContentRepository {

    Optional<Content> findById(ContentId contentId);

    void insert(Content content, ContentEvent event);

    boolean update(Content content, long expectedVersion, ContentEvent event);
}
