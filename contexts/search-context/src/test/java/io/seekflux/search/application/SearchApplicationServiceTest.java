package io.seekflux.search.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.feature.application.RealtimeFeaturePolicy;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureRead;
import io.seekflux.feature.domain.FeatureTopicScore;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import io.seekflux.search.port.in.SearchUnavailableException;
import io.seekflux.search.port.out.SearchCandidate;
import io.seekflux.search.port.out.SearchRetrievalResult;
import io.seekflux.search.port.out.SearchRetrievalSource;
import io.seekflux.search.port.out.SearchRetriever;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SearchApplicationServiceTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void fusesKeywordAndSemanticCandidatesWithTraceAndSafetyFiltering() {
        SearchCandidate camping = candidate("content-camping", "露营路线", List.of("露营"));
        SearchCandidate coffee = candidate("content-coffee", "咖啡教程", List.of("咖啡"));
        SearchCandidate blocked = candidate("content-blocked", "违规内容", List.of("moderation:blocked"));
        SearchRetriever retriever = request -> request.source() == SearchRetrievalSource.KEYWORD
                ? result(request.source(), List.of(camping, coffee, blocked), "bm25-v2", 5)
                : result(request.source(), List.of(coffee, camping, blocked), "knn-v1", 7);

        var service = service(retriever, Duration.ofMillis(200));
        var page = service.search(new SearchQuery("  杭州　露营  ", 0, 10));

        assertEquals("杭州 露营", page.query());
        assertEquals(2, page.total());
        assertEquals(List.of("content-camping", "content-coffee"),
                page.hits().stream().map(hit -> hit.contentId()).toList());
        assertEquals(List.of("KEYWORD", "SEMANTIC"), page.hits().getFirst().sources());
        assertEquals("DIRECT_HYBRID", page.trace().executionMode());
        assertEquals("direct-hybrid-v1", page.trace().policyVersion());
        assertFalse(page.trace().degraded());
        assertEquals(2, page.trace().channels().size());
    }

    @Test
    void fallsBackToSemanticWhenKeywordFails() {
        SearchRetriever retriever = request -> {
            if (request.source() == SearchRetrievalSource.KEYWORD) {
                throw new IllegalStateException("keyword unavailable");
            }
            return result(request.source(), List.of(candidate("semantic", "语义结果", List.of("知识"))), "knn-v1", 3);
        };

        var page = service(retriever, Duration.ofMillis(200))
                .search(new SearchQuery("知识", 0, 10));

        assertEquals("DIRECT_SEMANTIC_FALLBACK", page.trace().executionMode());
        assertTrue(page.trace().degraded());
        assertEquals(List.of("KEYWORD"), page.trace().unavailableSources());
        assertEquals("semantic", page.hits().getFirst().contentId());
    }

    @Test
    void cancelsATimedOutChannelAndReturnsTheOtherChannel() {
        SearchRetriever retriever = request -> {
            if (request.source() == SearchRetrievalSource.KEYWORD) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cancelled", interrupted);
                }
            }
            return result(request.source(), List.of(candidate("fast", "快速结果", List.of("科技"))), "v1", 1);
        };

        var page = service(retriever, Duration.ofMillis(40))
                .search(new SearchQuery("科技", 0, 10));

        assertEquals("DIRECT_SEMANTIC_FALLBACK", page.trace().executionMode());
        assertEquals("TIMED_OUT", page.trace().channels().getFirst().status());
        assertTrue(page.trace().tookMillis() < 300);
    }

    @Test
    void failsWithAStableExceptionWhenAllChannelsFail() {
        SearchRetriever retriever = request -> {
            throw new IllegalStateException("down");
        };

        assertThrows(SearchUnavailableException.class, () -> service(retriever, Duration.ofMillis(100))
                .search(new SearchQuery("露营", 0, 10)));
    }

    @Test
    void rejectsBlankOversizedAndDeepQueries() {
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery(" ", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery("露营", 0, 51));
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery("露营", 20, 10));
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery("露营\u0000", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new SearchQuery(
                "露营", 0, 10, java.util.stream.IntStream.range(0, 11)
                        .mapToObj(index -> "tag-" + index)
                        .toList()));
    }

    @Test
    void boostsFreshShortTermInterestAndExposesSnapshotTrace() {
        SearchCandidate coffee = candidate(
                "00000000-0000-0000-0000-000000000001", "咖啡", List.of("咖啡"));
        SearchCandidate camping = candidate(
                "00000000-0000-0000-0000-000000000002", "露营", List.of("露营"));
        SearchRetriever retriever = request -> result(
                request.source(), List.of(coffee, camping), "v1", 1);
        Instant now = Instant.parse("2026-08-11T10:00:00Z");
        RealtimeFeatureUseCase features = new RealtimeFeatureUseCase() {
            @Override
            public FeatureRead<ShortTermInterestSnapshot> shortTermInterest(String userId) {
                return FeatureRead.fresh(new ShortTermInterestSnapshot(
                        userId, List.of(new FeatureTopicScore("露营", 3)),
                        now.minusSeconds(1800), now, now, RealtimeFeaturePolicy.FEATURE_VERSION));
            }

            @Override
            public Map<UUID, FeatureRead<ContentHeatSnapshot>> contentHeat(Iterable<UUID> contentIds) {
                return Map.of();
            }
        };
        var service = new SearchApplicationService(
                retriever, executor, Duration.ofMillis(200), "direct-hybrid-v1", Set.of(), features);

        var page = service.search(new SearchQuery("生活", 0, 10, List.of(), "user-1"));

        assertEquals(camping.contentId(), page.hits().getFirst().contentId());
        assertEquals("FRESH", page.trace().realtimeFeatureStatus());
        assertEquals(RealtimeFeaturePolicy.FEATURE_VERSION, page.trace().realtimeFeatureVersion());
    }

    private SearchApplicationService service(SearchRetriever retriever, Duration deadline) {
        return new SearchApplicationService(
                retriever,
                executor,
                deadline,
                "direct-hybrid-v1",
                Set.of("moderation:blocked", "distribution:blocked"));
    }

    private static SearchRetrievalResult result(
            SearchRetrievalSource source,
            List<SearchCandidate> candidates,
            String version,
            long tookMillis) {
        return new SearchRetrievalResult(
                source,
                "seekflux-content-v1",
                version,
                tookMillis,
                candidates.size(),
                candidates);
    }

    private static SearchCandidate candidate(String id, String title, List<String> tags) {
        return new SearchCandidate(
                id,
                "creator",
                "https://media.example/" + id + ".mp4",
                title,
                "description",
                title + "摘要",
                tags,
                1,
                1.0,
                Instant.parse("2026-08-08T00:00:00Z"));
    }
}
