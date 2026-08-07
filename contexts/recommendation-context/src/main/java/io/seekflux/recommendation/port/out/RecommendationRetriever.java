package io.seekflux.recommendation.port.out;

import io.seekflux.ranking.domain.RankingCandidate;
import java.util.List;

public interface RecommendationRetriever {

    List<RankingCandidate> trending(int limit);

    List<RankingCandidate> byInterests(List<String> topics, int limit);

    List<RankingCandidate> similarTo(String contentId, int limit);
}
