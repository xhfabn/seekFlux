package io.seekflux.search.port.out;

import java.util.List;

public interface MediaSegmentRetriever {
    List<MediaSearchCandidate> retrieve(List<Double> vector, int limit);
}
