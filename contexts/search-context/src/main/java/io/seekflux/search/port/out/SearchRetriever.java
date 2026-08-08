package io.seekflux.search.port.out;

public interface SearchRetriever {

    SearchRetrievalResult retrieve(SearchRetrievalRequest request);
}
