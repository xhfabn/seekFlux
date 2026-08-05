package io.seekflux.search.application;

import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchUseCase;
import io.seekflux.search.port.out.SearchRetriever;
import java.util.Objects;
import reactor.core.publisher.Mono;

public final class SearchApplicationService implements SearchUseCase {

    private final SearchRetriever retriever;

    public SearchApplicationService(SearchRetriever retriever) {
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
    }

    @Override
    public Mono<SearchResultPage> search(SearchQuery query) {
        return retriever.search(Objects.requireNonNull(query, "search query must not be null"));
    }
}
