package io.seekflux.core.search.api;

public interface SearchUseCase {

    SearchResultPage search(SearchQuery query);
}
