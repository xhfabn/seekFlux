package io.seekflux.recommendation.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.ranking.application.RuleRankingService;
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
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RecommendationApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void returnsOnlyCandidatesWhoseTagsMatchTheUserProfile() {
        RecommendationRetriever retriever = new StubRetriever(false);
        var service = service(retriever);

        StepVerifier.create(service.feed(new FeedRequest("user-1", List.of("露营"), "seed", null, 1)))
                .assertNext(page -> {
                    assertFalse(page.degraded());
                    assertNotNull(page.nextCursor());
                    assertTrue(page.items().getFirst().sources().contains("INTEREST"));
                    assertTrue(page.items().getFirst().sources().contains("TRENDING"));
                    assertTrue(page.items().stream().allMatch(item -> item.tags().contains("露营")));
                })
                .verifyComplete();
    }

    @Test
    void keepsServingWhenOneRecallSourceFails() {
        var service = service(new StubRetriever(true));

        StepVerifier.create(service.feed(new FeedRequest("user-1", List.of("露营"), null, null, 10)))
                .assertNext(page -> {
                    assertTrue(page.degraded());
                    assertTrue(page.unavailableSources().contains("INTEREST"));
                    assertFalse(page.items().isEmpty());
                })
                .verifyComplete();
    }

    @Test
    void loadsThePersistedProfileWhenTheFeedRequestHasNoInterestOverride() {
        UserInterestRepository repository = new UserInterestRepository() {
            @Override
            public Mono<InterestProfile> findByUserId(String userId) {
                return Mono.just(new InterestProfile(userId, List.of("咖啡"), NOW));
            }

            @Override
            public Mono<Void> save(InterestProfile profile) {
                return Mono.empty();
            }
        };
        var service = service(new StubRetriever(false), repository);

        StepVerifier.create(service.feed(new FeedRequest("user-1", List.of(), null, null, 10)))
                .assertNext(page -> {
                    assertFalse(page.items().isEmpty());
                    assertTrue(page.items().stream().allMatch(item -> item.tags().contains("咖啡")));
                })
                .verifyComplete();
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
        var firstPage = service.feed(firstRequest).block();
        var secondPage = service.feed(new FeedRequest(
                "user-1", List.of("露营"), "seed", firstPage.nextCursor(), 1)).block();

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
        return new RecommendationApplicationService(
                retriever,
                new ExplicitInterestService(CLOCK, repository),
                new RuleRankingService(),
                new SignedRecommendationCursorCodec("test-secret-at-least-16-characters"),
                CLOCK,
                Duration.ofMillis(100));
    }

    private static RankingCandidate candidate(String id, RetrievalSource source, int rank, List<String> tags) {
        return new RankingCandidate(
                id, "creator-" + id, "https://media.example/" + id, "title-" + id, "",
                "summary-" + id, tags, 1, NOW.minusSeconds(rank * 60L), source, rank, 1.0 / rank);
    }

    private static final class StubRetriever implements RecommendationRetriever {
        private final boolean failInterests;

        private StubRetriever(boolean failInterests) {
            this.failInterests = failInterests;
        }

        @Override
        public Mono<List<RankingCandidate>> trending(int limit) {
            return Mono.just(List.of(
                    candidate("camp", RetrievalSource.TRENDING, 1, List.of("露营")),
                    candidate("coffee", RetrievalSource.TRENDING, 2, List.of("咖啡"))));
        }

        @Override
        public Mono<List<RankingCandidate>> byInterests(List<String> topics, int limit) {
            return failInterests
                    ? Mono.error(new IllegalStateException("interest source unavailable"))
                    : Mono.just(List.of(candidate("camp", RetrievalSource.INTEREST, 1, List.of("露营"))));
        }

        @Override
        public Mono<List<RankingCandidate>> similarTo(String contentId, int limit) {
            return Mono.just(List.of(candidate("similar", RetrievalSource.SIMILAR, 1, List.of("露营"))));
        }
    }

    private static final class EmptyInterestRepository implements UserInterestRepository {
        @Override
        public Mono<InterestProfile> findByUserId(String userId) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> save(InterestProfile profile) {
            return Mono.empty();
        }
    }
}
