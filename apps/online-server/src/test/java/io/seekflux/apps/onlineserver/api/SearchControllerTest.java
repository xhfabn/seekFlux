package io.seekflux.apps.onlineserver.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.search.port.in.SearchChannelTrace;
import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.in.SearchTrace;
import io.seekflux.search.port.in.SearchUnavailableException;
import io.seekflux.search.port.in.SearchUseCase;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SearchControllerTest {

    @Test
    void returnsTheDirectSearchTrace() throws Exception {
        AtomicReference<SearchQuery> captured = new AtomicReference<>();
        SearchUseCase useCase = query -> {
            captured.set(query);
            return page(query);
        };
        MockMvc client = MockMvcBuilders.standaloneSetup(new SearchController(useCase)).build();

        client.perform(get("/v1/search")
                        .queryParam("q", "杭州露营")
                        .queryParam("required_tags", "露营", "seekflux-eval-v1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trace.executionMode").value("DIRECT_HYBRID"))
                .andExpect(jsonPath("$.trace.policyVersion").value("direct-hybrid-v1"))
                .andExpect(jsonPath("$.trace.degraded").value(false))
                .andExpect(jsonPath("$.trace.channels[0].source").value("KEYWORD"));

        assertEquals(List.of("露营", "seekflux-eval-v1"), captured.get().requiredTags());
    }

    @Test
    void returnsAStableErrorWhenEveryRetrievalChannelFails() throws Exception {
        SearchUseCase useCase = query -> {
            throw new SearchUnavailableException();
        };
        MockMvc client = MockMvcBuilders.standaloneSetup(new SearchController(useCase))
                .setControllerAdvice(new SearchExceptionHandler())
                .build();

        client.perform(get("/v1/search").queryParam("q", "露营"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("all search retrieval channels are unavailable"));
    }

    @Test
    void rejectsADeepResultWindowWithTheStableSearchCode() throws Exception {
        SearchUseCase useCase = SearchControllerTest::page;
        MockMvc client = MockMvcBuilders.standaloneSetup(new SearchController(useCase))
                .setControllerAdvice(new SearchExceptionHandler())
                .build();

        client.perform(get("/v1/search")
                        .queryParam("q", "露营")
                        .queryParam("page", "20")
                        .queryParam("size", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_REQUEST"));
    }

    private static SearchResultPage page(SearchQuery query) {
        SearchChannelTrace channel = new SearchChannelTrace(
                "KEYWORD", "SUCCESS", "bm25-v2", 2, 0, null);
        SearchTrace trace = new SearchTrace(
                "search-test",
                "DIRECT_HYBRID",
                "seekflux-content-v1",
                "direct-hybrid-v1",
                2,
                false,
                List.of(),
                List.of(channel));
        return new SearchResultPage(query.text(), 0, query.page(), query.size(), 2, List.of(), trace);
    }
}
