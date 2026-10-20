package io.seekflux.recommendation.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.ranking.application.RuleRankingService;
import io.seekflux.feature.application.RealtimeFeaturePolicy;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureRead;
import io.seekflux.feature.domain.FeatureTopicScore;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import io.seekflux.ranking.domain.RankingCandidate;
import io.seekflux.ranking.domain.RetrievalSource;
import io.seekflux.recommendation.port.in.FeedRequest;
import io.seekflux.recommendation.port.out.RecommendationRetriever;
import io.seekflux.userinterest.application.ExplicitInterestService;
import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.out.UserInterestRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class RecommendationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void returnsOnlyCandidatesWhoseTagsMatchTheUserProfile() {
        RecommendationRetriever retriever = new StubRetriever(false);
        var service = service(retriever);

        var page = service.feed(new FeedRequest("user-1", List.of("露营"), "seed", null, 1));
        assertFalse(page.degraded());
        assertNotNull(page.nextCursor());
        assertTrue(page.items().getFirst().sources().contains("INTEREST"));
        assertTrue(page.items().getFirst().sources().contains("TRENDING"));
        assertTrue(page.items().stream().allMatch(item -> item.tags().contains("露营")));
    }

    @Test
    void keepsServingWhenOneRecallSourceFails() {
        var service = service(new StubRetriever(true));

        var page = service.feed(new FeedRequest("user-1", List.of("露营"), null, null, 10));
        assertTrue(page.degraded());
        assertTrue(page.unavailableSources().contains("INTEREST"));
        assertFalse(page.items().isEmpty());
    }

    @Test
    void degradesARecallThatExceedsItsTimeoutOnTheDedicatedExecutor() {
        RecommendationRetriever slowRetriever = new StubRetriever(false) {
            @Override
            public List<RankingCandidate> byInterests(List<String> topics, int limit) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return super.byInterests(topics, limit);
            }
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var service = service(
                    slowRetriever,
                    new EmptyInterestRepository(),
                    executor,
                    Duration.ofMillis(25));

            var page = service.feed(new FeedRequest("user-1", List.of("露营"), null, null, 10));

            assertTrue(page.degraded());
            assertTrue(page.unavailableSources().contains("INTEREST"));
            assertFalse(page.items().isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void loadsThePersistedProfileWhenTheFeedRequestHasNoInterestOverride() {
        UserInterestRepository repository = new UserInterestRepository() {
            @Override
            public Optional<InterestProfile> findByUserId(String userId) {
                return Optional.of(new InterestProfile(userId, List.of("咖啡"), NOW));
            }

            @Override
            public void save(InterestProfile profile) {
            }
        };
        var service = service(new StubRetriever(false), repository);

        var page = service.feed(new FeedRequest("user-1", List.of(), null, null, 10));
        assertFalse(page.items().isEmpty());
        assertTrue(page.items().stream().allMatch(item -> item.tags().contains("咖啡")));
    }

    @Test
    void mergesFreshShortTermTopicsAndReportsFeatureVersion() {
        RealtimeFeatureUseCase features = new RealtimeFeatureUseCase() {
            @Override
            public FeatureRead<ShortTermInterestSnapshot> shortTermInterest(String userId) {
                return FeatureRead.fresh(new ShortTermInterestSnapshot(
                        userId, List.of(new FeatureTopicScore("露营", 3.0)),
                        NOW.minusSeconds(1800), NOW, NOW, RealtimeFeaturePolicy.FEATURE_VERSION));
            }

            @Override
            public Map<UUID, FeatureRead<ContentHeatSnapshot>> contentHeat(Iterable<UUID> contentIds) {
                return Map.of();
            }
        };
        var service = new RecommendationApplicationService(
                new StubRetriever(false),
                new ExplicitInterestService(CLOCK, new EmptyInterestRepository()),
                new RuleRankingService(),
                new SignedRecommendationCursorCodec("test-secret-at-least-16-characters"),
                CLOCK, Duration.ofMillis(100), Runnable::run, features);

        var page = service.feed(new FeedRequest("user-1", List.of("咖啡"), null, null, 10));

        assertTrue(page.items().stream().anyMatch(item -> item.tags().contains("露营")));
        assertEquals("FRESH", page.realtimeFeatureStatus());
        assertEquals(RealtimeFeaturePolicy.FEATURE_VERSION, page.realtimeFeatureVersion());
    }

    @Test
    void rejectsCursorWhenRequestContextChanges() {
        var codec = new SignedRecommendationCursorCodec("test-secret-at-least-16-characters");
        String cursor = codec.encode(10, "fingerprint-a", NOW.plusSeconds(60));

        assertThrows(IllegalArgumentException.class, () -> codec.decode(cursor, "fingerprint-b", NOW));
    }

    @Test
    void rejectsTamperedAndExpiredCursors() {
        var codec = new SignedRecommendationCursorCodec("test-secret-at-least-16-characters");
        String cursor = codec.encode(10, "fingerprint", NOW.plusSeconds(60));
        String tampered = (cursor.startsWith("A") ? "B" : "A") + cursor.substring(1);

        assertThrows(IllegalArgumentException.class, () -> codec.decode(tampered, "fingerprint", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> codec.decode(cursor, "fingerprint", NOW.plusSeconds(61)));
    }

    @Test
    void cursorContinuesWithoutRepeatingTheFirstItem() {
        var service = service(new StubRetriever(false));
        FeedRequest firstRequest = new FeedRequest("user-1", List.of("露营"), "seed", null, 1);
        var firstPage = service.feed(firstRequest);
        var secondPage = service.feed(new FeedRequest(
                "user-1", List.of("露营"), "seed", firstPage.nextCursor(), 1));

        assertNotNull(firstPage);
        assertNotNull(secondPage);
        assertNotEquals(firstPage.items().getFirst().contentId(), secondPage.items().getFirst().contentId());
    }

    private static RecommendationApplicationService service(RecommendationRetriever retriever) {
        return service(retriever, new EmptyInterestRepository());
    }

    private static RecommendationApplicationService service(
            RecommendationRetriever retriever,
            UserInterestRepository repository) {
        return service(retriever, repository, Runnable::run, Duration.ofMillis(100));
    }

    private static RecommendationApplicationService service(
            RecommendationRetriever retriever,
            UserInterestRepository repository,
            Executor executor,
            Duration timeout) {
        return new RecommendationApplicationService(
                retriever,
                new ExplicitInterestService(CLOCK, repository),
                new RuleRankingService(),
                new SignedRecommendationCursorCodec("test-secret-at-least-16-characters"),
                CLOCK,
                timeout,
                executor);
    }

    private static RankingCandidate candidate(String id, RetrievalSource source, int rank, List<String> tags) {
        return new RankingCandidate(
                id, "creator-" + id, "https://media.example/" + id, "title-" + id, "",
                "summary-" + id, tags, 1, NOW.minusSeconds(rank * 60L), source, rank, 1.0 / rank);
    }

    private static class StubRetriever implements RecommendationRetriever {
        private final boolean failInterests;

        private StubRetriever(boolean failInterests) {
            this.failInterests = failInterests;
        }

        @Override
        public List<RankingCandidate> trending(int limit) {
            return List.of(
                    candidate("camp", RetrievalSource.TRENDING, 1, List.of("露营")),
                    candidate("coffee", RetrievalSource.TRENDING, 2, List.of("咖啡")));
        }

        @Override
        public List<RankingCandidate> byInterests(List<String> topics, int limit) {
            if (failInterests) {
                throw new IllegalStateException("interest source unavailable");
            }
            return List.of(candidate("camp", RetrievalSource.INTEREST, 1, List.of("露营")));
        }

        @Override
        public List<RankingCandidate> similarTo(String contentId, int limit) {
            return List.of(candidate("similar", RetrievalSource.SIMILAR, 1, List.of("露营")));
        }
    }

    private static final class EmptyInterestRepository implements UserInterestRepository {
        @Override
        public Optional<InterestProfile> findByUserId(String userId) {
            return Optional.empty();
        }

        @Override
        public void save(InterestProfile profile) {
        }
    }
}
