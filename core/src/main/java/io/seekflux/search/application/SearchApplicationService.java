package io.seekflux.search.application;

import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureRead;
import io.seekflux.feature.domain.FeatureReadStatus;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import io.seekflux.search.port.in.SearchChannelTrace;
import io.seekflux.search.port.in.SearchHitView;
import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchTrace;
import io.seekflux.search.port.in.SearchUnavailableException;
import io.seekflux.search.port.in.SearchUseCase;
import io.seekflux.search.port.out.SearchCandidate;
import io.seekflux.search.port.out.SearchRetrievalRequest;
import io.seekflux.search.port.out.SearchRetrievalResult;
import io.seekflux.search.port.out.SearchRetrievalSource;
import io.seekflux.search.port.out.SearchRetriever;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SearchApplicationService implements SearchUseCase {

    static final int CANDIDATE_LIMIT = 200;
    private static final double RRF_K = 60.0;
    private static final Map<SearchRetrievalSource, Double> SOURCE_WEIGHTS = Map.of(
            SearchRetrievalSource.KEYWORD, 1.0,
            SearchRetrievalSource.SEMANTIC, 0.85);

    private final SearchRetriever retriever;
    private final ExecutorService retrievalExecutor;
    private final Duration requestDeadline;
    private final String policyVersion;
    private final Set<String> blockedTags;
    private final RealtimeFeatureUseCase realtimeFeatures;

    public SearchApplicationService(
            SearchRetriever retriever,
            ExecutorService retrievalExecutor,
            Duration requestDeadline,
            String policyVersion,
            Set<String> blockedTags) {
        this(retriever, retrievalExecutor, requestDeadline, policyVersion, blockedTags,
                new MissingRealtimeFeatures());
    }

    public SearchApplicationService(
            SearchRetriever retriever,
            ExecutorService retrievalExecutor,
            Duration requestDeadline,
            String policyVersion,
            Set<String> blockedTags,
            RealtimeFeatureUseCase realtimeFeatures) {
        this.retriever = Objects.requireNonNull(retriever, "retriever must not be null");
        this.retrievalExecutor = Objects.requireNonNull(retrievalExecutor, "retrievalExecutor must not be null");
        this.requestDeadline = Objects.requireNonNull(requestDeadline, "requestDeadline must not be null");
        this.policyVersion = requireText(policyVersion, "policyVersion");
        this.blockedTags = normalizeTags(blockedTags);
        this.realtimeFeatures = Objects.requireNonNull(realtimeFeatures, "realtimeFeatures must not be null");
        if (requestDeadline.isNegative() || requestDeadline.isZero()) {
            throw new IllegalArgumentException("search request deadline must be positive");
        }
    }

    @Override
    public SearchResultPage search(SearchQuery query) {
        Objects.requireNonNull(query, "search query must not be null");
        long startedAt = System.nanoTime();
        long deadlineAt = startedAt + requestDeadline.toNanos();

        Map<SearchRetrievalSource, Future<SearchRetrievalResult>> futures = new EnumMap<>(SearchRetrievalSource.class);
        Map<SearchRetrievalSource, ChannelOutcome> immediate = new EnumMap<>(SearchRetrievalSource.class);
        for (SearchRetrievalSource source : SearchRetrievalSource.values()) {
            try {
                futures.put(source, retrievalExecutor.submit(() -> retriever.retrieve(
                        new SearchRetrievalRequest(
                                source,
                                query.text(),
                                query.requiredTags(),
                                CANDIDATE_LIMIT))));
            } catch (RejectedExecutionException rejected) {
                immediate.put(source, ChannelOutcome.rejected(source));
            }
        }

        List<ChannelOutcome> outcomes = new ArrayList<>();
        for (SearchRetrievalSource source : SearchRetrievalSource.values()) {
            ChannelOutcome rejected = immediate.get(source);
            outcomes.add(rejected == null
                    ? await(source, futures.get(source), deadlineAt)
                    : rejected);
        }

        List<ChannelOutcome> successful = outcomes.stream().filter(ChannelOutcome::successful).toList();
        if (successful.isEmpty()) {
            throw new SearchUnavailableException();
        }

        FeatureRead<ShortTermInterestSnapshot> shortInterest = realtimeFeatures.shortTermInterest(query.userId());
        List<FusedCandidate> eligible = fuse(successful).stream()
                .filter(candidate -> isEligible(candidate.candidate()))
                .limit(CANDIDATE_LIMIT)
                .toList();
        Map<UUID, FeatureRead<ContentHeatSnapshot>> heat = realtimeFeatures.contentHeat(
                eligible.stream().map(candidate -> parseUuid(candidate.candidate().contentId()))
                        .filter(Objects::nonNull).toList());
        List<FusedCandidate> fused = personalize(eligible, shortInterest, heat);
        int fromIndex = Math.min(query.page() * query.size(), fused.size());
        int toIndex = Math.min(fromIndex + query.size(), fused.size());
        List<SearchHitView> hits = fused.subList(fromIndex, toIndex).stream()
                .map(FusedCandidate::toView)
                .toList();

        long tookMillis = elapsedMillis(startedAt);
        List<String> unavailable = new ArrayList<>(outcomes.stream()
                .filter(outcome -> !outcome.successful())
                .map(outcome -> outcome.source().name())
                .toList());
        if (shortInterest.status() == FeatureReadStatus.STALE
                || shortInterest.status() == FeatureReadStatus.UNAVAILABLE
                || heat.values().stream().anyMatch(read -> read.status() == FeatureReadStatus.UNAVAILABLE)) {
            unavailable.add("REALTIME_FEATURES");
        }
        String executionMode = executionMode(successful);
        String indexVersion = successful.getFirst().result().indexVersion();
        List<SearchChannelTrace> channels = outcomes.stream().map(ChannelOutcome::toTrace).toList();
        SearchTrace trace = new SearchTrace(
                "search_" + UUID.randomUUID(),
                executionMode,
                indexVersion,
                policyVersion,
                tookMillis,
                !unavailable.isEmpty(),
                unavailable,
                channels,
                shortInterest.status().name(),
                shortInterest.value().map(ShortTermInterestSnapshot::featureVersion).orElse(null),
                shortInterest.value().map(ShortTermInterestSnapshot::computedAt).orElse(null));
        return new SearchResultPage(
                query.text(),
                fused.size(),
                query.page(),
                query.size(),
                tookMillis,
                hits,
                trace);
    }

    private ChannelOutcome await(
            SearchRetrievalSource source,
            Future<SearchRetrievalResult> future,
            long deadlineAt) {
        long remaining = Math.max(0, deadlineAt - System.nanoTime());
        try {
            SearchRetrievalResult result = future.get(remaining, TimeUnit.NANOSECONDS);
            if (result.source() != source) {
                return ChannelOutcome.failed(source, "RETRIEVAL_SOURCE_MISMATCH");
            }
            return ChannelOutcome.success(result);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return ChannelOutcome.timedOut(source, requestDeadline.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ChannelOutcome.failed(source, "RETRIEVAL_INTERRUPTED");
        } catch (ExecutionException failed) {
            return ChannelOutcome.failed(source, "RETRIEVAL_FAILED");
        }
    }

    private static List<FusedCandidate> fuse(List<ChannelOutcome> successful) {
        Map<String, MutableFusedCandidate> byContentId = new LinkedHashMap<>();
        for (ChannelOutcome outcome : successful) {
            List<SearchCandidate> candidates = outcome.result().candidates();
            for (int index = 0; index < candidates.size(); index++) {
                SearchCandidate candidate = candidates.get(index);
                int rank = index + 1;
                double contribution = SOURCE_WEIGHTS.get(outcome.source()) / (RRF_K + rank);
                byContentId.computeIfAbsent(
                                candidate.contentId(),
                                ignored -> new MutableFusedCandidate(candidate))
                        .add(outcome.source(), contribution);
            }
        }
        return byContentId.values().stream()
                .map(MutableFusedCandidate::freeze)
                .sorted(Comparator.comparingDouble(FusedCandidate::score).reversed()
                        .thenComparing(FusedCandidate::publishedAt, Comparator.reverseOrder())
                        .thenComparing(candidate -> candidate.candidate().contentId()))
                .toList();
    }

    private static List<FusedCandidate> personalize(
            List<FusedCandidate> candidates,
            FeatureRead<ShortTermInterestSnapshot> shortInterest,
            Map<UUID, FeatureRead<ContentHeatSnapshot>> heat) {
        Map<String, Double> topicScores = shortInterest.value().stream()
                .flatMap(snapshot -> snapshot.topics().stream())
                .collect(java.util.stream.Collectors.toMap(
                        topic -> topic.topic(), topic -> topic.score(), Math::max));
        double maxTopicScore = topicScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        return candidates.stream()
                .map(candidate -> {
                    double topicBoost = candidate.candidate().tags().stream()
                            .map(SearchApplicationService::normalizeTag)
                            .mapToDouble(tag -> topicScores.getOrDefault(tag, 0.0) / maxTopicScore)
                            .max().orElse(0.0) * 0.004;
                    UUID contentId = parseUuid(candidate.candidate().contentId());
                    double heatBoost = Optional.ofNullable(contentId)
                            .map(heat::get)
                            .flatMap(read -> read == null ? Optional.empty() : read.value())
                            .map(snapshot -> Math.log1p(Math.max(0, snapshot.score())) * 0.0005)
                            .orElse(0.0);
                    return new FusedCandidate(
                            candidate.candidate(), candidate.score() + topicBoost + heatBoost, candidate.sources());
                })
                .sorted(Comparator.comparingDouble(FusedCandidate::score).reversed()
                        .thenComparing(FusedCandidate::publishedAt, Comparator.reverseOrder())
                        .thenComparing(candidate -> candidate.candidate().contentId()))
                .toList();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private boolean isEligible(SearchCandidate candidate) {
        if (blockedTags.isEmpty()) {
            return true;
        }
        return candidate.tags().stream()
                .map(SearchApplicationService::normalizeTag)
                .noneMatch(blockedTags::contains);
    }

    private static String executionMode(List<ChannelOutcome> successful) {
        boolean keyword = successful.stream().anyMatch(outcome -> outcome.source() == SearchRetrievalSource.KEYWORD);
        boolean semantic = successful.stream().anyMatch(outcome -> outcome.source() == SearchRetrievalSource.SEMANTIC);
        if (keyword && semantic) {
            return "DIRECT_HYBRID";
        }
        return keyword ? "DIRECT_KEYWORD_FALLBACK" : "DIRECT_SEMANTIC_FALLBACK";
    }

    private static long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private static Set<String> normalizeTags(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(SearchApplicationService::normalizeTag)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static String normalizeTag(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String requireText(String value, String name) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private record ChannelOutcome(
            SearchRetrievalSource source,
            SearchRetrievalResult result,
            String status,
            long tookMillis,
            String errorCode) {

        private static ChannelOutcome success(SearchRetrievalResult result) {
            return new ChannelOutcome(result.source(), result, "SUCCESS", result.tookMillis(), null);
        }

        private static ChannelOutcome timedOut(SearchRetrievalSource source, long tookMillis) {
            return new ChannelOutcome(source, null, "TIMED_OUT", tookMillis, "RETRIEVAL_TIMEOUT");
        }

        private static ChannelOutcome failed(SearchRetrievalSource source, String errorCode) {
            return new ChannelOutcome(source, null, "FAILED", 0, errorCode);
        }

        private static ChannelOutcome rejected(SearchRetrievalSource source) {
            return new ChannelOutcome(source, null, "REJECTED", 0, "RETRIEVAL_REJECTED");
        }

        private boolean successful() {
            return result != null;
        }

        private SearchChannelTrace toTrace() {
            return new SearchChannelTrace(
                    source.name(),
                    status,
                    result == null ? "unavailable" : result.retrieverVersion(),
                    tookMillis,
                    result == null ? 0 : result.candidates().size(),
                    errorCode);
        }
    }

    private static final class MutableFusedCandidate {

        private final SearchCandidate candidate;
        private final Set<SearchRetrievalSource> sources = new LinkedHashSet<>();
        private double score;

        private MutableFusedCandidate(SearchCandidate candidate) {
            this.candidate = candidate;
        }

        private void add(SearchRetrievalSource source, double contribution) {
            sources.add(source);
            score += contribution;
        }

        private FusedCandidate freeze() {
            return new FusedCandidate(candidate, score, List.copyOf(sources));
        }
    }

    private record FusedCandidate(
            SearchCandidate candidate,
            double score,
            List<SearchRetrievalSource> sources) {

        private SearchHitView toView() {
            return new SearchHitView(
                    candidate.contentId(),
                    candidate.creatorId(),
                    candidate.contentType(),
                    candidate.mediaUri(),
                    candidate.assetUris(),
                    candidate.title(),
                    candidate.description(),
                    candidate.body(),
                    candidate.summary(),
                    candidate.tags(),
                    candidate.sourceProvider(),
                    candidate.sourcePageUri(),
                    candidate.sourceAuthor(),
                    candidate.licenseName(),
                    candidate.profileVersion(),
                    score,
                    sources.stream().map(Enum::name).toList(),
                    candidate.publishedAt());
        }

        private java.time.Instant publishedAt() {
            return candidate.publishedAt();
        }
    }

    private static final class MissingRealtimeFeatures implements RealtimeFeatureUseCase {
        @Override
        public FeatureRead<ShortTermInterestSnapshot> shortTermInterest(String userId) {
            return FeatureRead.empty(FeatureReadStatus.MISSING);
        }

        @Override
        public Map<UUID, FeatureRead<ContentHeatSnapshot>> contentHeat(Iterable<UUID> contentIds) {
            return Map.of();
        }
    }
}
