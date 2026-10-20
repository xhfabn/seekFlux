package io.seekflux.core.search.domain;

public interface SearchRetriever {

    SearchRetrievalResult retrieve(SearchRetrievalRequest request);
}
