package io.seekflux.ranking.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.ranking.domain.RankingCandidate;
import io.seekflux.ranking.domain.RankingRequest;
import io.seekflux.ranking.domain.RetrievalSource;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleRankingServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");

    @Test
    void mergesRecallSourcesAndBoostsMatchingInterests() {
        var service = new RuleRankingService();
        var candidates = List.of(
                candidate("camp", "creator-a", List.of("露营"), RetrievalSource.TRENDING, 2),
                candidate("camp", "creator-a", List.of("露营"), RetrievalSource.INTEREST, 1),
                candidate("coffee", "creator-b", List.of("咖啡"), RetrievalSource.TRENDING, 1));

        var result = service.rank(candidates, new RankingRequest(List.of("露营"), 10, NOW));

        assertEquals("camp", result.getFirst().contentId());
        assertEquals(2, result.getFirst().sources().size());
        assertTrue(result.getFirst().reason().contains("露营"));
    }

    @Test
    void limitsOneCreatorAndAvoidsAdjacentTopicsWhenAlternativesExist() {
        var service = new RuleRankingService();
        var candidates = List.of(
                candidate("a1", "creator-a", List.of("露营"), RetrievalSource.TRENDING, 1),
                candidate("a2", "creator-a", List.of("露营"), RetrievalSource.TRENDING, 2),
                candidate("b1", "creator-b", List.of("咖啡"), RetrievalSource.TRENDING, 3),
                candidate("a3", "creator-a", List.of("摄影"), RetrievalSource.TRENDING, 4));

        var result = service.rank(candidates, new RankingRequest(List.of(), 10, NOW));

        assertEquals(List.of("a1", "b1", "a2"), result.stream().map(item -> item.contentId()).toList());
    }

    private static RankingCandidate candidate(
            String id, String creator, List<String> tags, RetrievalSource source, int rank) {
        return new RankingCandidate(
                id, creator, "https://media.example/" + id, "title-" + id, "", "summary-" + id,
                tags, 1, NOW.minusSeconds(rank * 60L), source, rank, 1.0 / rank);
    }
}
