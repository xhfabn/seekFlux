package io.seekflux.search.port.in;

import reactor.core.publisher.Mono;

public interface SearchUseCase {

    Mono<SearchResultPage> search(SearchQuery query);
}
