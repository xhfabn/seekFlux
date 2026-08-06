package io.seekflux.recommendation.port.out;

import io.seekflux.ranking.domain.RankingCandidate;
import java.util.List;
import reactor.core.publisher.Mono;

public interface RecommendationRetriever {

    Mono<List<RankingCandidate>> trending(int limit);

    Mono<List<RankingCandidate>> byInterests(List<String> topics, int limit);

    Mono<List<RankingCandidate>> similarTo(String contentId, int limit);
}
