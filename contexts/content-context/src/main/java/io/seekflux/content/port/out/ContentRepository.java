package io.seekflux.content.port.out;

import io.seekflux.content.domain.Content;
import io.seekflux.content.domain.ContentId;
import reactor.core.publisher.Mono;

public interface ContentRepository {

    Mono<Content> findById(ContentId contentId);

    Mono<Void> insert(Content content, ContentEvent event);

    Mono<Boolean> update(Content content, long expectedVersion, ContentEvent event);
}
