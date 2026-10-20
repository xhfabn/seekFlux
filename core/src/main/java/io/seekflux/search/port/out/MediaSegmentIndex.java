package io.seekflux.search.port.out;

public interface MediaSegmentIndex {
    void upsert(MediaSegmentDocument document);
    void deleteByContentId(String contentId);
}
