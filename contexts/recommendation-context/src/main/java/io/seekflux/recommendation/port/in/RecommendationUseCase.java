package io.seekflux.recommendation.port.in;

public interface RecommendationUseCase {

    RecommendationPage feed(FeedRequest request);

    RecommendationPage similar(SimilarContentRequest request);
}
