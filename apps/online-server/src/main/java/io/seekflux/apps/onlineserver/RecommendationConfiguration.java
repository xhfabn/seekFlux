package io.seekflux.apps.onlineserver;

import io.seekflux.ranking.application.RuleRankingService;
import io.seekflux.ranking.port.in.RankingUseCase;
import io.seekflux.recommendation.application.RecommendationApplicationService;
import io.seekflux.recommendation.application.SignedRecommendationCursorCodec;
import io.seekflux.recommendation.port.in.RecommendationUseCase;
import io.seekflux.recommendation.port.out.RecommendationRetriever;
import io.seekflux.userinterest.application.ExplicitInterestService;
import io.seekflux.userinterest.port.in.UserInterestUseCase;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RecommendationConfiguration {

    @Bean
    Clock recommendationClock() {
        return Clock.systemUTC();
    }

    @Bean
    UserInterestUseCase userInterestUseCase(Clock recommendationClock) {
        return new ExplicitInterestService(recommendationClock);
    }

    @Bean
    RankingUseCase rankingUseCase() {
        return new RuleRankingService();
    }

    @Bean
    SignedRecommendationCursorCodec recommendationCursorCodec(
            @Value("${seekflux.recommendation.cursor-secret}") String secret) {
        return new SignedRecommendationCursorCodec(secret);
    }

    @Bean
    RecommendationUseCase recommendationUseCase(
            RecommendationRetriever retriever,
            UserInterestUseCase userInterest,
            RankingUseCase ranking,
            SignedRecommendationCursorCodec cursorCodec,
            Clock recommendationClock,
            @Value("${seekflux.recommendation.source-timeout-ms:250}") long sourceTimeoutMillis) {
        return new RecommendationApplicationService(
                retriever,
                userInterest,
                ranking,
                cursorCodec,
                recommendationClock,
                Duration.ofMillis(sourceTimeoutMillis));
    }
}
