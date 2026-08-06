package io.seekflux.recommendation.port.in;

import reactor.core.publisher.Mono;

public interface RecommendationUseCase {

    Mono<RecommendationPage> feed(FeedRequest request);

    Mono<RecommendationPage> similar(SimilarContentRequest request);
}
