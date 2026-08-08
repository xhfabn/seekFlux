package io.seekflux.apps.agentserver;

import io.seekflux.search.application.SearchApplicationService;
import io.seekflux.search.port.in.SearchUseCase;
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

@Configuration
class AgentSearchConfiguration {

    @Bean(name = "agentSearchRetrievalExecutor", destroyMethod = "shutdown")
    ExecutorService agentSearchRetrievalExecutor(
            @Value("${seekflux.search.retrieval-pool.core-size:2}") int coreSize,
            @Value("${seekflux.search.retrieval-pool.max-size:4}") int maxSize,
            @Value("${seekflux.search.retrieval-pool.queue-capacity:50}") int queueCapacity) {
        return boundedExecutor("seekflux-agent-search-", coreSize, maxSize, queueCapacity);
    }

    @Bean
    SearchUseCase directSearchUseCase(
            SearchRetriever retriever,
            @Qualifier("agentSearchRetrievalExecutor") ExecutorService retrievalExecutor,
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
                blocked);
    }

    static ExecutorService boundedExecutor(
            String threadPrefix,
            int coreSize,
            int maxSize,
            int queueCapacity) {
        if (coreSize < 1 || maxSize < coreSize || queueCapacity < 1) {
            throw new IllegalArgumentException("invalid bounded executor configuration");
        }
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, threadPrefix + sequence.incrementAndGet());
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
}
