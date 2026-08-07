package io.seekflux.recommendation.application;

import io.seekflux.ranking.domain.RankedCandidate;
import io.seekflux.ranking.domain.RankingCandidate;
import io.seekflux.ranking.domain.RankingRequest;
import io.seekflux.ranking.port.in.RankingUseCase;
import io.seekflux.recommendation.port.in.FeedRequest;
import io.seekflux.recommendation.port.in.RecommendationItemView;
import io.seekflux.recommendation.port.in.RecommendationPage;
import io.seekflux.recommendation.port.in.RecommendationUseCase;
import io.seekflux.recommendation.port.in.SimilarContentRequest;
import io.seekflux.recommendation.port.out.RecommendationRetriever;
import io.seekflux.userinterest.domain.InterestProfile;
import io.seekflux.userinterest.port.in.UserInterestUseCase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

public final class RecommendationApplicationService implements RecommendationUseCase {

    private static final int CANDIDATE_LIMIT = 200;
    private static final Duration CURSOR_TTL = Duration.ofMinutes(15);

    private final RecommendationRetriever retriever;
    private final UserInterestUseCase userInterest;
    private final RankingUseCase ranking;
    private final SignedRecommendationCursorCodec cursorCodec;
    private final Clock clock;
    private final Duration sourceTimeout;

    public RecommendationApplicationService(
            RecommendationRetriever retriever,
            UserInterestUseCase userInterest,
            RankingUseCase ranking,
            SignedRecommendationCursorCodec cursorCodec,
            Clock clock,
            Duration sourceTimeout) {
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        this.userInterest = Objects.requireNonNull(userInterest, "userInterest must not be null");
        this.ranking = Objects.requireNonNull(ranking, "ranking must not be null");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.sourceTimeout = Objects.requireNonNull(sourceTimeout, "sourceTimeout must not be null");
        if (sourceTimeout.isNegative() || sourceTimeout.isZero()) {
            throw new IllegalArgumentException("source timeout must be positive");
        }
    }

    @Override
    public Mono<RecommendationPage> feed(FeedRequest request) {
        Objects.requireNonNull(request, "feed request must not be null");
        return userInterest.resolve(request.userId(), request.explicitInterests()).flatMap(profile -> {
            String fingerprint = fingerprint("feed", request.userId(), profile.topics(), request.seedContentId());
            int offset = cursorCodec.decode(request.cursor(), fingerprint, clock.instant());
            if (profile.topics().isEmpty()) {
                return Mono.just(page(List.of(), profile, fingerprint, offset, request.pageSize()));
            }

            Mono<SourceResult> trending = retrieve("TRENDING", () -> retriever.trending(CANDIDATE_LIMIT));
            Mono<SourceResult> interests = retrieve(
                    "INTEREST", () -> retriever.byInterests(profile.topics(), CANDIDATE_LIMIT));
            Mono<SourceResult> similar = request.seedContentId() == null
                    ? Mono.just(SourceResult.empty("SIMILAR"))
                    : retrieve("SIMILAR", () -> retriever.similarTo(request.seedContentId(), CANDIDATE_LIMIT));

            return Mono.zip(trending, interests, similar)
                    .map(results -> page(
                            List.of(results.getT1(), results.getT2(), results.getT3()),
                            profile,
                            fingerprint,
                            offset,
                            request.pageSize()));
        });
    }

    @Override
    public Mono<RecommendationPage> similar(SimilarContentRequest request) {
        Objects.requireNonNull(request, "similar content request must not be null");
        return userInterest.resolve(request.userId(), request.explicitInterests()).flatMap(profile -> {
            String fingerprint = fingerprint("similar", request.userId(), profile.topics(), request.contentId());
            int offset = cursorCodec.decode(request.cursor(), fingerprint, clock.instant());
            if (profile.topics().isEmpty()) {
                return Mono.just(page(List.of(), profile, fingerprint, offset, request.pageSize()));
            }

            Mono<SourceResult> similar = retrieve(
                    "SIMILAR", () -> retriever.similarTo(request.contentId(), CANDIDATE_LIMIT));
            Mono<SourceResult> fallback = retrieve("TRENDING", () -> retriever.trending(CANDIDATE_LIMIT));
            return Mono.zip(similar, fallback).map(results -> {
                SourceResult primary = results.getT1();
                SourceResult trending = results.getT2();
                List<SourceResult> selected = primary.candidates().isEmpty()
                        ? List.of(primary, trending)
                        : List.of(primary);
                return page(selected, profile, fingerprint, offset, request.pageSize());
            });
        });
    }

    private Mono<SourceResult> retrieve(String source, Supplier<Mono<List<RankingCandidate>>> operation) {
        return Mono.defer(operation)
                .timeout(sourceTimeout)
                .map(candidates -> new SourceResult(source, candidates, false))
                .onErrorResume(error -> Mono.just(new SourceResult(source, List.of(), true)));
    }

    private RecommendationPage page(
            List<SourceResult> sources,
            InterestProfile profile,
            String fingerprint,
            int offset,
            int pageSize) {
        List<RankingCandidate> candidates = sources.stream()
                .flatMap(source -> source.candidates().stream())
                .filter(candidate -> matchesProfile(candidate, profile))
                .toList();
        List<RankedCandidate> ranked = ranking.rank(
                candidates,
                new RankingRequest(profile.topics(), CANDIDATE_LIMIT, clock.instant()));
        int fromIndex = Math.min(offset, ranked.size());
        int toIndex = Math.min(fromIndex + pageSize, ranked.size());
        List<RecommendationItemView> items = ranked.subList(fromIndex, toIndex).stream()
                .map(RecommendationApplicationService::toView)
                .toList();
        String nextCursor = toIndex < ranked.size()
                ? cursorCodec.encode(toIndex, fingerprint, clock.instant().plus(CURSOR_TTL))
                : null;
        List<String> unavailable = sources.stream()
                .filter(SourceResult::failed)
                .map(SourceResult::source)
                .toList();
        return new RecommendationPage(
                "req_" + UUID.randomUUID(),
                items,
                nextCursor,
                !unavailable.isEmpty(),
                unavailable);
    }

    private static boolean matchesProfile(RankingCandidate candidate, InterestProfile profile) {
        if (profile.topics().isEmpty()) {
            return false;
        }
        var normalizedTags = candidate.tags().stream()
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        return profile.topics().stream().anyMatch(normalizedTags::contains);
    }

    private static RecommendationItemView toView(RankedCandidate item) {
        return new RecommendationItemView(
                item.contentId(), item.creatorId(), item.mediaUri(), item.title(), item.description(),
                item.summary(), item.tags(), item.profileVersion(), item.publishedAt(), item.score(),
                item.sources().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet()),
                item.reason());
    }

    private static String fingerprint(String scenario, String userId, List<String> topics, String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String value = scenario + "\n" + userId + "\n" + String.join(",", topics) + "\n" + (seed == null ? "" : seed);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("cannot fingerprint recommendation request", exception);
        }
    }

    private record SourceResult(String source, List<RankingCandidate> candidates, boolean failed) {
        private SourceResult {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        private static SourceResult empty(String source) {
            return new SourceResult(source, List.of(), false);
        }
    }
}
