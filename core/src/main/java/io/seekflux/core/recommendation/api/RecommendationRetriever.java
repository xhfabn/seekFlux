package io.seekflux.core.recommendation.api;

import io.seekflux.core.recommendation.api.RankingCandidate;
import java.util.List;

public interface RecommendationRetriever {

    List<RankingCandidate> trending(int limit);

    List<RankingCandidate> byInterests(List<String> topics, int limit);

    List<RankingCandidate> similarTo(String contentId, int limit);
}
