package io.seekflux.apps.onlineserver;

import io.seekflux.search.application.SearchApplicationService;
import io.seekflux.search.application.MultimodalSearchApplicationService;
import io.seekflux.feature.port.in.RealtimeFeatureUseCase;
import io.seekflux.search.port.in.SearchUseCase;
import io.seekflux.search.port.in.MultimodalSearchUseCase;
import io.seekflux.search.port.out.MediaEmbeddingPort;
import io.seekflux.search.port.out.MediaSegmentRetriever;
import io.seekflux.search.port.out.SearchRetriever;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Configuration
class SearchConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "seekflux.multimodal", name = "enabled", havingValue = "true")
    MultimodalSearchUseCase multimodalSearchUseCase(
            MediaEmbeddingPort embeddingPort,
            MediaSegmentRetriever retriever,
            @Value("${seekflux.multimodal.query-segments:8}") int querySegments) {
        return new MultimodalSearchApplicationService(embeddingPort, retriever, querySegments);
    }

    @Bean(name = "searchRetrievalExecutor", destroyMethod = "shutdown")
    ExecutorService searchRetrievalExecutor(
            @Value("${seekflux.search.retrieval-pool.core-size:2}") int coreSize,
            @Value("${seekflux.search.retrieval-pool.max-size:4}") int maxSize,
            @Value("${seekflux.search.retrieval-pool.queue-capacity:50}") int queueCapacity) {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 1) {
            throw new IllegalArgumentException("invalid search retrieval pool configuration");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "seekflux-search-" + sequence.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        };
        return new ThreadPoolExecutor(
                coreSize,
                maxSize,
                60,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean
    SearchUseCase searchUseCase(
            SearchRetriever retriever,
            @Qualifier("searchRetrievalExecutor") ExecutorService retrievalExecutor,
            RealtimeFeatureUseCase realtimeFeatures,
            @Value("${seekflux.search.request-deadline-ms:1200}") long requestDeadlineMillis,
            @Value("${seekflux.search.policy-version:direct-hybrid-v1}") String policyVersion,
            @Value("${seekflux.search.blocked-tags:moderation:blocked,distribution:blocked,违规,下架}")
                    String blockedTags) {
        Set<String> blocked = Arrays.stream(blockedTags.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new SearchApplicationService(
                retriever,
                retrievalExecutor,
                Duration.ofMillis(requestDeadlineMillis),
                policyVersion,
                blocked,
                realtimeFeatures);
    }
}
