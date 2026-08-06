package io.seekflux.apps.onlineserver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.recommendation.port.in.FeedRequest;
import io.seekflux.recommendation.port.in.RecommendationPage;
import io.seekflux.recommendation.port.in.RecommendationUseCase;
import io.seekflux.recommendation.port.in.SimilarContentRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class RecommendationControllerTest {

    @Test
    void mapsFeedParametersIntoTheUseCase() {
        AtomicReference<FeedRequest> captured = new AtomicReference<>();
        RecommendationUseCase useCase = new RecommendationUseCase() {
            @Override
            public Mono<RecommendationPage> feed(FeedRequest request) {
                captured.set(request);
                return Mono.just(new RecommendationPage("req-1", List.of(), null, false, List.of()));
            }

            @Override
            public Mono<RecommendationPage> similar(SimilarContentRequest request) {
                return Mono.error(new UnsupportedOperationException());
            }
        };
        WebTestClient client = WebTestClient.bindToController(new RecommendationController(useCase)).build();

        client.get().uri(builder -> builder.path("/v1/feed")
                        .queryParam("page_size", 2)
                        .queryParam("interests", "露营,亲子")
                        .queryParam("seed_content_id", "seed-1")
                        .build())
                .header("X-User-Id", "user-1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.requestId").isEqualTo("req-1")
                .jsonPath("$.degraded").isEqualTo(false);

        assertEquals("user-1", captured.get().userId());
        assertEquals(List.of("露营", "亲子"), captured.get().explicitInterests());
        assertEquals("seed-1", captured.get().seedContentId());
        assertEquals(2, captured.get().pageSize());
    }

    @Test
    void returnsStableRecommendationErrorForInvalidCursor() {
        RecommendationUseCase useCase = new RecommendationUseCase() {
            @Override
            public Mono<RecommendationPage> feed(FeedRequest request) {
                return Mono.error(new IllegalArgumentException("invalid or expired recommendation cursor"));
            }

            @Override
            public Mono<RecommendationPage> similar(SimilarContentRequest request) {
                return Mono.error(new UnsupportedOperationException());
            }
        };
        WebTestClient client = WebTestClient.bindToController(new RecommendationController(useCase))
                .controllerAdvice(new SearchExceptionHandler())
                .build();

        client.get().uri("/v1/feed?cursor=invalid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("INVALID_RECOMMENDATION_REQUEST")
                .jsonPath("$.message").isEqualTo("invalid or expired recommendation cursor");
    }
}
