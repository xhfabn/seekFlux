package io.seekflux.core.search.domain;

public interface SearchIndex {

    void upsert(SearchDocument document);

    void delete(String contentId);
}
