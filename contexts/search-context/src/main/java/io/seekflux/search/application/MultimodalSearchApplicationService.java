package io.seekflux.search.application;

import io.seekflux.search.port.in.MultimodalSearchQuery;
import io.seekflux.search.port.in.MultimodalSearchResult;
import io.seekflux.search.port.in.MultimodalSearchUseCase;
import io.seekflux.search.port.in.SearchUnavailableException;
import io.seekflux.search.port.out.MediaEmbeddingPort;
import io.seekflux.search.port.out.MediaSearchCandidate;
import io.seekflux.search.port.out.MediaSegmentRetriever;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MultimodalSearchApplicationService implements MultimodalSearchUseCase {

    private final MediaEmbeddingPort embeddingPort;
    private final MediaSegmentRetriever retriever;
    private final int querySegments;

    public MultimodalSearchApplicationService(
            MediaEmbeddingPort embeddingPort, MediaSegmentRetriever retriever, int querySegments) {
        this.embeddingPort = embeddingPort;
        this.retriever = retriever;
        this.querySegments = querySegments;
    }

    @Override
    public MultimodalSearchResult search(MultimodalSearchQuery query) {
        final io.seekflux.search.port.out.MediaEmbeddingBatch embeddings;
        try {
            embeddings = embeddingPort.embed(query.modality(), query.input(), querySegments);
        } catch (RuntimeException error) {
            throw new SearchUnavailableException("multimodal embedding service is unavailable", error);
        }
        Map<String, MediaSearchCandidate> bestByContent = new LinkedHashMap<>();
        for (var segment : embeddings.segments()) {
            final java.util.List<MediaSearchCandidate> candidates;
            try {
                candidates = retriever.retrieve(segment.vector(), Math.min(200, query.size() * 5));
            } catch (RuntimeException error) {
                throw new SearchUnavailableException("multimodal media index is unavailable", error);
            }
            for (var candidate : candidates) {
                bestByContent.merge(candidate.contentId(), candidate,
                        (left, right) -> left.score() >= right.score() ? left : right);
            }
        }
        var items = bestByContent.values().stream()
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(query.size())
                .toList();
        return new MultimodalSearchResult(
                query.modality().name(), embeddings.modelVersion(), embeddings.segments().size(), items);
    }
}
