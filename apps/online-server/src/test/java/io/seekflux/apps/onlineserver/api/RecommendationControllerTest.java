package io.seekflux.apps.onlineserver.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.seekflux.recommendation.port.in.FeedRequest;
import io.seekflux.recommendation.port.in.RecommendationPage;
import io.seekflux.recommendation.port.in.RecommendationUseCase;
import io.seekflux.recommendation.port.in.SimilarContentRequest;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecommendationControllerTest {

    @Test
    void mapsFeedParametersIntoTheUseCase() throws Exception {
        AtomicReference<FeedRequest> captured = new AtomicReference<>();
        RecommendationUseCase useCase = new RecommendationUseCase() {
            @Override
            public RecommendationPage feed(FeedRequest request) {
                captured.set(request);
                return new RecommendationPage("req-1", List.of(), null, false, List.of());
            }

            @Override
            public RecommendationPage similar(SimilarContentRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        MockMvc client = MockMvcBuilders
                .standaloneSetup(new RecommendationController(useCase))
                .build();

        client.perform(get("/v1/feed")
                        .queryParam("page_size", "2")
                        .queryParam("interests", "露营,亲子")
                        .queryParam("seed_content_id", "seed-1")
                        .header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.degraded").value(false));

        assertEquals("user-1", captured.get().userId());
        assertEquals(List.of("露营", "亲子"), captured.get().explicitInterests());
        assertEquals("seed-1", captured.get().seedContentId());
        assertEquals(2, captured.get().pageSize());
    }

    @Test
    void returnsStableRecommendationErrorForInvalidCursor() throws Exception {
        RecommendationUseCase useCase = new RecommendationUseCase() {
            @Override
            public RecommendationPage feed(FeedRequest request) {
                throw new IllegalArgumentException("invalid or expired recommendation cursor");
            }

            @Override
            public RecommendationPage similar(SimilarContentRequest request) {
                throw new UnsupportedOperationException();
            }
        };
        MockMvc client = MockMvcBuilders.standaloneSetup(new RecommendationController(useCase))
                .setControllerAdvice(new SearchExceptionHandler())
                .build();

        client.perform(get("/v1/feed").queryParam("cursor", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RECOMMENDATION_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("invalid or expired recommendation cursor"));
    }
}
