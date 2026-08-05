package io.seekflux.search.port.out;

import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import reactor.core.publisher.Mono;

public interface SearchRetriever {

    Mono<SearchResultPage> search(SearchQuery query);
}
