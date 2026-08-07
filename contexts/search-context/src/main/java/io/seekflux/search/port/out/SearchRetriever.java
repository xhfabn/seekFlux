package io.seekflux.search.port.out;

import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
public interface SearchRetriever {

    SearchResultPage search(SearchQuery query);
}
