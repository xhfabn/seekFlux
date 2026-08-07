package io.seekflux.search.port.in;

public interface SearchUseCase {

    SearchResultPage search(SearchQuery query);
}
