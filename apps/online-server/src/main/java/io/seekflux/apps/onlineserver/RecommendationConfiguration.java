package io.seekflux.apps.onlineserver;

import io.seekflux.ranking.application.RuleRankingService;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import io.seekflux.ranking.port.in.RankingUseCase;
import io.seekflux.recommendation.application.RecommendationApplicationService;
import io.seekflux.recommendation.application.SignedRecommendationCursorCodec;
import io.seekflux.recommendation.port.in.RecommendationUseCase;
import io.seekflux.recommendation.port.out.RecommendationRetriever;
import io.seekflux.userinterest.application.ExplicitInterestService;
import io.seekflux.userinterest.port.in.UserInterestUseCase;
import io.seekflux.userinterest.port.out.UserInterestRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
class RecommendationConfiguration {

    @Bean
    Clock recommendationClock() {
        return Clock.systemUTC();
    }

    @Bean
    UserInterestUseCase userInterestUseCase(
            Clock recommendationClock,
            UserInterestRepository userInterestRepository) {
        return new ExplicitInterestService(recommendationClock, userInterestRepository);
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

    @Bean(name = "recommendationRecallExecutor")
    ThreadPoolTaskExecutor recommendationRecallExecutor(
            @Value("${seekflux.recommendation.recall-pool.core-size:4}") int coreSize,
            @Value("${seekflux.recommendation.recall-pool.max-size:8}") int maxSize,
            @Value("${seekflux.recommendation.recall-pool.queue-capacity:100}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("seekflux-recall-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }

    @Bean
    RecommendationUseCase recommendationUseCase(
            RecommendationRetriever retriever,
            UserInterestUseCase userInterest,
            RankingUseCase ranking,
            SignedRecommendationCursorCodec cursorCodec,
            Clock recommendationClock,
            @Value("${seekflux.recommendation.source-timeout-ms:1500}") long sourceTimeoutMillis,
            @Qualifier("recommendationRecallExecutor") Executor recallExecutor,
            RealtimeFeatureUseCase realtimeFeatures) {
        return new RecommendationApplicationService(
                retriever,
                userInterest,
                ranking,
                cursorCodec,
                recommendationClock,
                Duration.ofMillis(sourceTimeoutMillis),
                recallExecutor,
                realtimeFeatures);
    }
}
