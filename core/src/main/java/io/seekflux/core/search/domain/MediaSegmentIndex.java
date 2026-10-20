package io.seekflux.core.search.domain;

public interface MediaSegmentIndex {
    void upsert(MediaSegmentDocument document);
    void deleteByContentId(String contentId);
}
