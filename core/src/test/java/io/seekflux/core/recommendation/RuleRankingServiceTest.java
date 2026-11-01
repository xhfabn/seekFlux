package io.seekflux.core.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.core.recommendation.api.RankingCandidate;
import io.seekflux.core.recommendation.api.RankingRequest;
import io.seekflux.core.recommendation.api.RetrievalSource;
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

    @Test
    void sourceOnlyImportIdentityDoesNotConsumeRealAuthorQuota() {
        var service = new RuleRankingService();
        var candidates = List.of(
                imported("q1", "qilin-dataset", "qilin-first-five-v1", "露营", 1),
                imported("q2", "qilin-dataset", "qilin-first-five-v1", "摄影", 2),
                imported("q3", "qilin-dataset", "qilin-first-five-v1", "露营", 3),
                imported("q4", "qilin-import", "qilin", "摄影", 4),
                imported("e1", "external-import", "manifest", "露营", 5),
                imported("e2", "external-import", "manifest", "摄影", 6),
                imported("e3", "external-import", "manifest", "露营", 7),
                imported("a1", "real-author", "qilin", "摄影", 8),
                imported("a2", "real-author", "qilin", "露营", 9),
                imported("a3", "real-author", "qilin", "摄影", 10),
                imported("b1", "qilin-dataset", "other-provider", "露营", 11),
                imported("b2", "qilin-dataset", "other-provider", "摄影", 12),
                imported("b3", "qilin-dataset", "other-provider", "露营", 13));

        var result = service.rank(candidates, new RankingRequest(List.of(), 20, NOW));

        assertEquals(11, result.size());
        assertEquals(4, result.stream().filter(item -> item.sourceProvider().startsWith("qilin")
                && item.creatorId().startsWith("qilin-")).count());
        assertEquals(3, result.stream().filter(item -> item.creatorId().equals("external-import")).count());
        assertEquals(2, result.stream().filter(item -> item.creatorId().equals("real-author")).count());
        assertEquals(2, result.stream().filter(item -> item.sourceProvider().equals("other-provider")).count());
        assertTrue(result.stream().anyMatch(item -> item.creatorId().equals("qilin-dataset")
                && item.sourceAuthor().equals("THUIR Qilin dataset")));
    }

    private static RankingCandidate imported(String id, String creator, String provider, String tag, int rank) {
        String uri = "https://media.example/" + id;
        return new RankingCandidate(id, creator, "ARTICLE", uri, List.of(uri), "title-" + id, "", "",
                "summary-" + id, List.of(tag), provider, "", "THUIR Qilin dataset", "", 1,
                NOW.minusSeconds(rank * 60L), RetrievalSource.INTEREST, rank, 1.0 / rank);
    }

    private static RankingCandidate candidate(
            String id, String creator, List<String> tags, RetrievalSource source, int rank) {
        return new RankingCandidate(
                id, creator, "https://media.example/" + id, "title-" + id, "", "summary-" + id,
                tags, 1, NOW.minusSeconds(rank * 60L), source, rank, 1.0 / rank);
    }
}
