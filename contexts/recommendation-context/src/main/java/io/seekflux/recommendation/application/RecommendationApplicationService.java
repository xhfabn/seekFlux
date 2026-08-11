package io.seekflux.recommendation.application;

import io.seekflux.feature.domain.FeatureRead;
import io.seekflux.feature.domain.FeatureReadStatus;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public final class RecommendationApplicationService implements RecommendationUseCase {

    private static final int CANDIDATE_LIMIT = 200;
    private static final Duration CURSOR_TTL = Duration.ofMinutes(15);

    private final RecommendationRetriever retriever;
    private final UserInterestUseCase userInterest;
    private final RankingUseCase ranking;
    private final SignedRecommendationCursorCodec cursorCodec;
    private final Clock clock;
    private final Duration sourceTimeout;
    private final Executor recallExecutor;
    private final RealtimeFeatureUseCase realtimeFeatures;

    public RecommendationApplicationService(
            RecommendationRetriever retriever,
            UserInterestUseCase userInterest,
            RankingUseCase ranking,
            SignedRecommendationCursorCodec cursorCodec,
            Clock clock,
            Duration sourceTimeout,
            Executor recallExecutor) {
        this(retriever, userInterest, ranking, cursorCodec, clock, sourceTimeout, recallExecutor,
                new MissingRealtimeFeatures());
    }

    public RecommendationApplicationService(
            RecommendationRetriever retriever,
            UserInterestUseCase userInterest,
            RankingUseCase ranking,
            SignedRecommendationCursorCodec cursorCodec,
            Clock clock,
            Duration sourceTimeout,
            Executor recallExecutor,
            RealtimeFeatureUseCase realtimeFeatures) {
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        this.userInterest = Objects.requireNonNull(userInterest, "userInterest must not be null");
        this.ranking = Objects.requireNonNull(ranking, "ranking must not be null");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.sourceTimeout = Objects.requireNonNull(sourceTimeout, "sourceTimeout must not be null");
        this.recallExecutor = Objects.requireNonNull(recallExecutor, "recallExecutor must not be null");
        this.realtimeFeatures = Objects.requireNonNull(realtimeFeatures, "realtimeFeatures must not be null");
        if (sourceTimeout.isNegative() || sourceTimeout.isZero()) {
            throw new IllegalArgumentException("source timeout must be positive");
        }
    }

    @Override
    public RecommendationPage feed(FeedRequest request) {
        Objects.requireNonNull(request, "feed request must not be null");
        InterestProfile explicit = userInterest.resolve(request.userId(), request.explicitInterests());
        FeatureRead<ShortTermInterestSnapshot> realtime = realtimeFeatures.shortTermInterest(request.userId());
        InterestProfile profile = merge(explicit, realtime);
        String fingerprint = fingerprint("feed", request.userId(), profile.topics(), request.seedContentId());
        int offset = cursorCodec.decode(request.cursor(), fingerprint, clock.instant());
        if (profile.topics().isEmpty()) {
            return page(List.of(), profile, realtime, fingerprint, offset, request.pageSize());
        }

        CompletableFuture<SourceResult> trending = retrieve(
                "TRENDING", () -> retriever.trending(CANDIDATE_LIMIT));
        CompletableFuture<SourceResult> interests = retrieve(
                "INTEREST", () -> retriever.byInterests(profile.topics(), CANDIDATE_LIMIT));
        CompletableFuture<SourceResult> similar = request.seedContentId() == null
                ? CompletableFuture.completedFuture(SourceResult.empty("SIMILAR"))
                : retrieve("SIMILAR", () -> retriever.similarTo(request.seedContentId(), CANDIDATE_LIMIT));

        return page(
                List.of(trending.join(), interests.join(), similar.join()),
                profile,
                realtime,
                fingerprint,
                offset,
                request.pageSize());
    }

    @Override
    public RecommendationPage similar(SimilarContentRequest request) {
        Objects.requireNonNull(request, "similar content request must not be null");
        InterestProfile explicit = userInterest.resolve(request.userId(), request.explicitInterests());
        FeatureRead<ShortTermInterestSnapshot> realtime = realtimeFeatures.shortTermInterest(request.userId());
        InterestProfile profile = merge(explicit, realtime);
        String fingerprint = fingerprint("similar", request.userId(), profile.topics(), request.contentId());
        int offset = cursorCodec.decode(request.cursor(), fingerprint, clock.instant());
        if (profile.topics().isEmpty()) {
            return page(List.of(), profile, realtime, fingerprint, offset, request.pageSize());
        }

        CompletableFuture<SourceResult> similar = retrieve(
                "SIMILAR", () -> retriever.similarTo(request.contentId(), CANDIDATE_LIMIT));
        CompletableFuture<SourceResult> fallback = retrieve(
                "TRENDING", () -> retriever.trending(CANDIDATE_LIMIT));
        SourceResult primary = similar.join();
        SourceResult trending = fallback.join();
        List<SourceResult> selected = primary.candidates().isEmpty()
                ? List.of(primary, trending)
                : List.of(primary);
        return page(selected, profile, realtime, fingerprint, offset, request.pageSize());
    }

    private CompletableFuture<SourceResult> retrieve(
            String source,
            Supplier<List<RankingCandidate>> operation) {
        return CompletableFuture
                .supplyAsync(() -> new SourceResult(source, operation.get(), false), recallExecutor)
                .completeOnTimeout(
                        new SourceResult(source, List.of(), true),
                        sourceTimeout.toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS)
                .exceptionally(error -> new SourceResult(source, List.of(), true));
    }

    private RecommendationPage page(
            List<SourceResult> sources,
            InterestProfile profile,
            FeatureRead<ShortTermInterestSnapshot> realtime,
            String fingerprint,
            int offset,
            int pageSize) {
        List<RankingCandidate> candidates = sources.stream()
                .flatMap(source -> source.candidates().stream())
                .filter(candidate -> matchesProfile(candidate, profile))
                .toList();
        Map<UUID, FeatureRead<ContentHeatSnapshot>> heatReads = realtimeFeatures.contentHeat(
                candidates.stream().map(candidate -> parseUuid(candidate.contentId()))
                        .filter(Objects::nonNull).toList());
        Map<String, Double> heat = heatReads.entrySet().stream()
                .flatMap(entry -> entry.getValue().value().stream()
                        .map(snapshot -> Map.entry(entry.getKey().toString(), snapshot.score())))
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, Map.Entry::getValue, Math::max));
        List<RankedCandidate> ranked = ranking.rank(
                candidates,
                new RankingRequest(profile.topics(), CANDIDATE_LIMIT, clock.instant(), heat));
        int fromIndex = Math.min(offset, ranked.size());
        int toIndex = Math.min(fromIndex + pageSize, ranked.size());
        List<RecommendationItemView> items = ranked.subList(fromIndex, toIndex).stream()
                .map(RecommendationApplicationService::toView)
                .toList();
        String nextCursor = toIndex < ranked.size()
                ? cursorCodec.encode(toIndex, fingerprint, clock.instant().plus(CURSOR_TTL))
                : null;
        List<String> unavailable = new ArrayList<>(sources.stream()
                .filter(SourceResult::failed)
                .map(SourceResult::source)
                .toList());
        if (realtime.status() == FeatureReadStatus.STALE
                || realtime.status() == FeatureReadStatus.UNAVAILABLE
                || heatReads.values().stream().anyMatch(read -> read.status() == FeatureReadStatus.UNAVAILABLE)) {
            unavailable.add("REALTIME_FEATURES");
        }
        Optional<ShortTermInterestSnapshot> snapshot = realtime.value();
        return new RecommendationPage(
                "req_" + UUID.randomUUID(),
                items,
                nextCursor,
                !unavailable.isEmpty(),
                unavailable,
                realtime.status().name(),
                snapshot.map(ShortTermInterestSnapshot::featureVersion).orElse(null),
                snapshot.map(ShortTermInterestSnapshot::computedAt).orElse(null));
    }

    private InterestProfile merge(
            InterestProfile explicit,
            FeatureRead<ShortTermInterestSnapshot> realtime) {
        if (realtime.status() != FeatureReadStatus.FRESH || realtime.value().isEmpty()) {
            return explicit;
        }
        LinkedHashSet<String> topics = new LinkedHashSet<>(explicit.topics());
        realtime.value().orElseThrow().topics().forEach(topic -> topics.add(topic.topic()));
        return new InterestProfile(
                explicit.userId(),
                topics.stream().limit(20).toList(),
                realtime.value().orElseThrow().computedAt().isAfter(explicit.updatedAt())
                        ? realtime.value().orElseThrow().computedAt()
                        : explicit.updatedAt());
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

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
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

    private static final class MissingRealtimeFeatures implements RealtimeFeatureUseCase {
        @Override
        public FeatureRead<ShortTermInterestSnapshot> shortTermInterest(String userId) {
            return FeatureRead.empty(FeatureReadStatus.MISSING);
        }

        @Override
        public Map<java.util.UUID, FeatureRead<io.seekflux.feature.domain.ContentHeatSnapshot>> contentHeat(
                Iterable<java.util.UUID> contentIds) {
            return Map.of();
        }
    }
}
