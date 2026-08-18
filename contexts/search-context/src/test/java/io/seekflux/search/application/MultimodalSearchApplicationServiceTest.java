package io.seekflux.search.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.search.port.in.MultimodalSearchQuery;
import io.seekflux.search.port.out.MediaEmbeddingBatch;
import io.seekflux.search.port.out.MediaEmbeddingSegment;
import io.seekflux.search.port.out.MediaModality;
import io.seekflux.search.port.out.MediaSearchCandidate;
import io.seekflux.search.port.out.MediaSegmentRetriever;
import java.util.List;
import org.junit.jupiter.api.Test;

class MultimodalSearchApplicationServiceTest {

    @Test
    void fusesMultipleQuerySegmentsByBestContentScore() {
        var service = new MultimodalSearchApplicationService(
                (modality, input, maxSegments) -> new MediaEmbeddingBatch(
                        "siglip-test", 2, List.of(
                                new MediaEmbeddingSegment(0, 0, 5000, "", List.of(1.0, 0.0)),
                                new MediaEmbeddingSegment(1, 5000, 10000, "", List.of(0.0, 1.0)))),
                (vector, limit) -> vector.getFirst() > 0.5
                        ? List.of(candidate("content-a", 0.7), candidate("content-b", 0.6))
                        : List.of(candidate("content-a", 0.9), candidate("content-c", 0.8)),
                8);

        var result = service.search(new MultimodalSearchQuery(MediaModality.VIDEO, "https://media/query.mp4", 2));

        assertEquals("siglip-test", result.modelVersion());
        assertEquals(2, result.querySegments());
        assertEquals(List.of("content-a", "content-c"),
                result.items().stream().map(MediaSearchCandidate::contentId).toList());
        assertEquals(List.of("VISUAL"), result.retrievalChannels());
        assertEquals(false, result.degraded());
    }

    @Test
    void textQueryFusesSharedVisualSpaceWithUnderstandingEvidence() {
        MediaSegmentRetriever retriever = new MediaSegmentRetriever() {
            @Override
            public List<MediaSearchCandidate> retrieve(List<Double> vector, int limit) {
                return List.of(candidate("content-a", 0.7, "VISUAL"));
            }

            @Override
            public List<MediaSearchCandidate> retrieveText(String query, int limit) {
                return List.of(candidate("content-a", 4.2, "UNDERSTANDING_TEXT"),
                        candidate("content-b", 3.9, "UNDERSTANDING_TEXT"));
            }
        };
        var service = new MultimodalSearchApplicationService(
                (modality, input, maxSegments) -> new MediaEmbeddingBatch("siglip-test", 2,
                        List.of(new MediaEmbeddingSegment(0, 0, 0, "", List.of(1.0, 0.0)))),
                retriever, 8);

        var result = service.search(new MultimodalSearchQuery(MediaModality.TEXT, "咖啡教程", 2));

        assertEquals(List.of("content-a", "content-b"),
                result.items().stream().map(MediaSearchCandidate::contentId).toList());
        assertEquals(List.of("VISUAL", "UNDERSTANDING_TEXT"), result.items().getFirst().matchedChannels());
        assertEquals(List.of("VISUAL", "UNDERSTANDING_TEXT"), result.retrievalChannels());
    }

    private static MediaSearchCandidate candidate(String id, double score) {
        return candidate(id, score, "VISUAL");
    }

    private static MediaSearchCandidate candidate(String id, double score, String channel) {
        return new MediaSearchCandidate(
                id, "VIDEO", "https://media/" + id, List.of(), id, id, List.of(),
                0, 5000, "", score, "siglip-test", List.of(channel), List.of());
    }
}
