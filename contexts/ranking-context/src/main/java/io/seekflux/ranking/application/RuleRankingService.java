package io.seekflux.ranking.application;

import io.seekflux.ranking.domain.RankedCandidate;
import io.seekflux.ranking.domain.RankingCandidate;
import io.seekflux.ranking.domain.RankingRequest;
import io.seekflux.ranking.domain.RetrievalSource;
import io.seekflux.ranking.port.in.RankingUseCase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class RuleRankingService implements RankingUseCase {

    private static final int RRF_K = 60;
    private static final int MAX_PER_CREATOR = 2;

    @Override
    public List<RankedCandidate> rank(List<RankingCandidate> candidates, RankingRequest request) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        Map<String, MergedCandidate> merged = new LinkedHashMap<>();
        for (RankingCandidate candidate : candidates) {
            merged.computeIfAbsent(candidate.contentId(), ignored -> new MergedCandidate(candidate))
                    .add(candidate);
        }

        List<ScoredCandidate> scored = merged.values().stream()
                .map(candidate -> score(candidate, request))
                .sorted(Comparator.comparingDouble(ScoredCandidate::score).reversed()
                        .thenComparing(item -> item.candidate().publishedAt(), Comparator.reverseOrder())
                        .thenComparing(item -> item.candidate().contentId()))
                .collect(Collectors.toCollection(ArrayList::new));
        return diversify(scored, request.limit());
    }

    private static ScoredCandidate score(MergedCandidate merged, RankingRequest request) {
        RankingCandidate candidate = merged.representative;
        Set<String> interests = request.interestTopics().stream()
                .map(topic -> topic.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        List<String> matchedInterests = candidate.tags().stream()
                .filter(tag -> interests.contains(tag.toLowerCase(Locale.ROOT)))
                .distinct()
                .toList();
        long ageDays = Math.max(0, Duration.between(candidate.publishedAt(), request.rankedAt()).toDays());
        double freshness = Math.max(0.0, 1.0 - ageDays / 30.0);
        double score = merged.rrfScore * 100.0
                + matchedInterests.size() * 0.35
                + freshness * 0.25
                + Math.log1p(Math.max(0, request.contentHeat().getOrDefault(candidate.contentId(), 0.0))) * 0.08;

        String sourceReason = merged.sources.stream()
                .sorted()
                .map(RuleRankingService::sourceLabel)
                .collect(Collectors.joining("、"));
        String reason = matchedInterests.isEmpty()
                ? "来自" + sourceReason + "召回"
                : "匹配兴趣「" + String.join("、", matchedInterests) + "」，来自" + sourceReason + "召回";
        if (request.contentHeat().getOrDefault(candidate.contentId(), 0.0) > 0) {
            reason += "，结合实时热度";
        }
        return new ScoredCandidate(candidate, score, Set.copyOf(merged.sources), reason);
    }

    private static List<RankedCandidate> diversify(List<ScoredCandidate> candidates, int limit) {
        List<RankedCandidate> result = new ArrayList<>();
        Map<String, Integer> creatorCounts = new HashMap<>();
        String previousPrimaryTag = null;

        while (!candidates.isEmpty() && result.size() < limit) {
            int selectedIndex = findCandidate(candidates, creatorCounts, previousPrimaryTag, true);
            if (selectedIndex < 0) {
                selectedIndex = findCandidate(candidates, creatorCounts, previousPrimaryTag, false);
            }
            if (selectedIndex < 0) {
                break;
            }
            ScoredCandidate selected = candidates.remove(selectedIndex);
            RankingCandidate item = selected.candidate();
            creatorCounts.merge(item.creatorId(), 1, Integer::sum);
            previousPrimaryTag = primaryTag(item);
            result.add(new RankedCandidate(
                    item.contentId(), item.creatorId(), item.mediaUri(), item.title(), item.description(),
                    item.summary(), item.tags(), item.profileVersion(), item.publishedAt(), selected.score(),
                    selected.sources(), selected.reason()));
        }
        return List.copyOf(result);
    }

    private static int findCandidate(
            List<ScoredCandidate> candidates,
            Map<String, Integer> creatorCounts,
            String previousPrimaryTag,
            boolean avoidAdjacentTag) {
        for (int index = 0; index < candidates.size(); index++) {
            RankingCandidate candidate = candidates.get(index).candidate();
            if (creatorCounts.getOrDefault(candidate.creatorId(), 0) >= MAX_PER_CREATOR) {
                continue;
            }
            if (avoidAdjacentTag && previousPrimaryTag != null
                    && previousPrimaryTag.equals(primaryTag(candidate))) {
                continue;
            }
            return index;
        }
        return -1;
    }

    private static String primaryTag(RankingCandidate candidate) {
        return candidate.tags().isEmpty() ? "" : candidate.tags().getFirst().toLowerCase(Locale.ROOT);
    }

    private static String sourceLabel(RetrievalSource source) {
        return switch (source) {
            case TRENDING -> "热门";
            case INTEREST -> "兴趣";
            case SIMILAR -> "相似内容";
        };
    }

    private static final class MergedCandidate {
        private final RankingCandidate representative;
        private final EnumSet<RetrievalSource> sources = EnumSet.noneOf(RetrievalSource.class);
        private double rrfScore;

        private MergedCandidate(RankingCandidate representative) {
            this.representative = representative;
        }

        private MergedCandidate add(RankingCandidate candidate) {
            if (sources.add(candidate.source())) {
                double sourceWeight = switch (candidate.source()) {
                    case TRENDING -> 0.9;
                    case INTEREST -> 1.2;
                    case SIMILAR -> 1.1;
                };
                rrfScore += sourceWeight / (RRF_K + candidate.sourceRank());
            }
            return this;
        }
    }

    private record ScoredCandidate(
            RankingCandidate candidate,
            double score,
            Set<RetrievalSource> sources,
            String reason) {
    }
}
