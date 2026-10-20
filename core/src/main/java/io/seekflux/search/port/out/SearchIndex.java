package io.seekflux.search.port.out;

public interface SearchIndex {

    void upsert(SearchDocument document);

    void delete(String contentId);
}
