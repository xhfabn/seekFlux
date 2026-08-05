package io.seekflux.search.port.out;

import reactor.core.publisher.Mono;

public interface SearchIndex {

    Mono<Void> upsert(SearchDocument document);

    Mono<Void> delete(String contentId);
}
