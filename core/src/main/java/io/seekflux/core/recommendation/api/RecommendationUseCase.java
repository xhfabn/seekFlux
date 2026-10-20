package io.seekflux.core.recommendation.api;

public interface RecommendationUseCase {

    RecommendationPage feed(FeedRequest request);

    RecommendationPage similar(SimilarContentRequest request);
}
