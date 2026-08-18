package io.seekflux.search.application;

import io.seekflux.search.port.in.MultimodalSearchQuery;
import io.seekflux.search.port.in.MultimodalSearchResult;
import io.seekflux.search.port.in.MultimodalSearchUseCase;
import io.seekflux.search.port.in.SearchUnavailableException;
import io.seekflux.search.port.out.MediaEmbeddingPort;
import io.seekflux.search.port.out.MediaModality;
import io.seekflux.search.port.out.MediaSearchCandidate;
import io.seekflux.search.port.out.MediaSegmentRetriever;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public final class MultimodalSearchApplicationService implements MultimodalSearchUseCase {

    private static final double RRF_K = 60.0;
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

        Map<String, FusionState> fused = new LinkedHashMap<>();
        List<String> unavailable = new ArrayList<>();
        int successfulVisualRoutes = 0;
        for (var segment : embeddings.segments()) {
            try {
                mergeRanked(fused, retriever.retrieve(segment.vector(), Math.min(200, query.size() * 5)), 1.0);
                successfulVisualRoutes++;
            } catch (RuntimeException error) {
                unavailable.add("VISUAL_SEGMENT_" + segment.ordinal());
            }
        }
        if (successfulVisualRoutes == 0) {
            throw new SearchUnavailableException("multimodal media index is unavailable", null);
        }

        if (query.modality() == MediaModality.TEXT) {
            try {
                mergeRanked(fused, retriever.retrieveText(query.input(), Math.min(200, query.size() * 5)), 0.8);
            } catch (RuntimeException error) {
                unavailable.add("UNDERSTANDING_TEXT");
            }
        }

        var items = fused.values().stream()
                .sorted((left, right) -> {
                    int fusedOrder = Double.compare(right.score, left.score);
                    return fusedOrder != 0 ? fusedOrder : Double.compare(right.bestRawScore, left.bestRawScore);
                })
                .limit(query.size())
                .map(FusionState::candidate)
                .toList();
        List<String> channels = query.modality() == MediaModality.TEXT
                ? List.of("VISUAL", "UNDERSTANDING_TEXT") : List.of("VISUAL");
        return new MultimodalSearchResult(query.modality().name(), embeddings.modelVersion(),
                embeddings.segments().size(), channels, !unavailable.isEmpty(), unavailable, items);
    }

    private static void mergeRanked(Map<String, FusionState> fused, List<MediaSearchCandidate> ranked, double weight) {
        for (int rank = 0; rank < ranked.size(); rank++) {
            MediaSearchCandidate candidate = ranked.get(rank);
            FusionState state = fused.computeIfAbsent(candidate.contentId(), ignored -> new FusionState(candidate));
            state.score += weight / (RRF_K + rank + 1);
            if (candidate.score() > state.bestRawScore) {
                state.best = candidate;
                state.bestRawScore = candidate.score();
            }
            state.channels.addAll(candidate.matchedChannels());
        }
    }

    private static final class FusionState {
        private MediaSearchCandidate best;
        private double bestRawScore;
        private double score;
        private final LinkedHashSet<String> channels = new LinkedHashSet<>();

        private FusionState(MediaSearchCandidate candidate) {
            this.best = candidate;
            this.bestRawScore = candidate.score();
        }

        private MediaSearchCandidate candidate() {
            return best.withFusion(score, List.copyOf(channels));
        }
    }
}
