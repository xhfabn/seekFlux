package io.seekflux.apps.onlineserver.api.error;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.seekflux.apps.onlineserver.api.feature.RealtimeFeatureController;
import io.seekflux.apps.onlineserver.api.interaction.InteractionController;
import io.seekflux.apps.onlineserver.api.interest.UserInterestController;
import io.seekflux.apps.onlineserver.api.recommendation.RecommendationController;
import io.seekflux.apps.onlineserver.api.search.MultimodalSearchController;
import io.seekflux.apps.onlineserver.api.search.SearchController;
import io.seekflux.core.feature.api.RealtimeFeatureUseCase;
import io.seekflux.core.feature.api.ReportInteractionsUseCase;
import io.seekflux.core.recommendation.api.RecommendationUseCase;
import io.seekflux.core.recommendation.api.UserInterestUseCase;
import io.seekflux.core.search.api.MultimodalSearchUseCase;
import io.seekflux.core.search.api.SearchUseCase;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OnlineExceptionHandlerTest {

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void returnsTheMatchedControllersErrorCode(MockHttpServletRequestBuilder request, String code) throws Exception {
        RealtimeFeatureUseCase features = mock(RealtimeFeatureUseCase.class);
        when(features.shortTermInterest(anyString())).thenThrow(new IllegalArgumentException("invalid user"));
        UserInterestUseCase interests = mock(UserInterestUseCase.class);
        when(interests.resolve(anyString(), any())).thenThrow(new IllegalArgumentException("invalid user"));
        RecommendationUseCase recommendations = mock(RecommendationUseCase.class);
        when(recommendations.feed(any())).thenThrow(new IllegalArgumentException("invalid cursor"));
        when(recommendations.similar(any())).thenThrow(new IllegalArgumentException("invalid cursor"));
        var mvc = MockMvcBuilders.standaloneSetup(
                        new SearchController(mock(SearchUseCase.class)),
                        new MultimodalSearchController(mock(MultimodalSearchUseCase.class)),
                        new InteractionController(mock(ReportInteractionsUseCase.class)),
                        new UserInterestController(interests),
                        new RealtimeFeatureController(features),
                        new RecommendationController(recommendations))
                .setControllerAdvice(new OnlineExceptionHandler())
                .build();

        var result = mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.timestamp").isString())
                .andReturn();
        var body = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
        Instant.parse(body.get("timestamp").asText());
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of(post("/v1/search").contentType(MediaType.APPLICATION_JSON).content("{"),
                        "INVALID_SEARCH_REQUEST"),
                Arguments.of(post("/v1/search/multimodal").contentType(MediaType.APPLICATION_JSON).content("{"),
                        "INVALID_SEARCH_REQUEST"),
                Arguments.of(post("/seekflux/v1/search").contextPath("/seekflux")
                                .contentType(MediaType.APPLICATION_JSON).content("{"),
                        "INVALID_SEARCH_REQUEST"),
                Arguments.of(post("/v1/interactions:batch").header("Idempotency-Key", "batch-1")
                                .header("X-User-Id", "user-1").contentType(MediaType.APPLICATION_JSON).content("{"),
                        "INVALID_INTERACTION_REQUEST"),
                Arguments.of(put("/v1/users/demo/interest-profile").contentType(MediaType.APPLICATION_JSON).content("{"),
                        "INVALID_INTEREST_REQUEST"),
                Arguments.of(get("/v1/users/demo/interest-profile"), "INVALID_INTEREST_REQUEST"),
                Arguments.of(get("/v1/features/users/demo/short-term-interest"), "INVALID_FEATURE_REQUEST"),
                Arguments.of(get("/v1/feed"), "INVALID_RECOMMENDATION_REQUEST"),
                Arguments.of(get("/v1/contents/demo/similar"), "INVALID_RECOMMENDATION_REQUEST"));
    }
}
