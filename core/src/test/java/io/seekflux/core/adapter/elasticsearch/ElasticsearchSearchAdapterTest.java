package io.seekflux.core.adapter.elasticsearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.seekflux.core.search.domain.SearchRetrievalRequest;
import io.seekflux.core.search.domain.SearchRetrievalSource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ElasticsearchSearchAdapterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void longChineseQueryReturnsResultsWithoutExpensiveWildcardExpansion() throws Exception {
        try (var fixture = new SearchFixture()) {
            String query = "女生独立计划，学会读水表再也不怕被坑";
            var result = fixture.adapter.retrieve(new SearchRetrievalRequest(
                    SearchRetrievalSource.KEYWORD, query, List.of("生活"), 20));

            assertEquals(1, result.total());
            assertEquals("elasticsearch-bm25-v3", result.retrieverVersion());
            assertEquals("content-1", result.candidates().getFirst().contentId());
            JsonNode bool = fixture.request.get().path("query").path("bool");
            assertEquals(query, bool.path("should").get(0).path("multi_match").path("query").asText());
            assertFalse(bool.path("should").get(0).path("multi_match").has("fuzziness"));
            assertEquals(query, bool.path("should").get(1).path("match_phrase").path("title").path("query").asText());
            assertFalse(fixture.request.get().toString().contains("wildcard"));
            assertEquals("生活", bool.path("filter").get(0).path("term").path("tags").asText());
        }
    }

    @Test
    void shortLatinQueriesRetainBoundedTypoTolerance() throws Exception {
        try (var fixture = new SearchFixture()) {
            fixture.adapter.retrieve(new SearchRetrievalRequest(
                    SearchRetrievalSource.KEYWORD, "coffe", List.of(), 10));
            JsonNode query = fixture.request.get().path("query").path("bool").path("should").get(0).path("multi_match");
            assertEquals("AUTO", query.path("fuzziness").asText());
            assertEquals(1, query.path("prefix_length").asInt());
            assertEquals(20, query.path("max_expansions").asInt());
            assertEquals("*coffe*", fixture.request.get().path("query").path("bool").path("should")
                    .get(2).path("wildcard").path("searchable.raw").path("value").asText());
            assertTrue(fixture.request.get().path("query").path("bool").path("filter").isMissingNode());
        }
    }

    private final class SearchFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<JsonNode> request = new AtomicReference<>();
        private final ElasticsearchSearchAdapter adapter;

        SearchFixture() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
            SemanticVectorEncoder encoder = new SemanticVectorEncoder() {
                public int dimensions() { return 64; }
                public String version() { return "fixture"; }
                public double[] encode(String text) { return new double[64]; }
            };
            adapter = new ElasticsearchSearchAdapter(RestClient.builder(), mapper, encoder,
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-index", 1000, 2000);
        }

        private void handle(HttpExchange exchange) throws java.io.IOException {
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
                return;
            }
            String response;
            if (exchange.getRequestURI().getPath().endsWith("/_mapping")) {
                response = "{\"test-index\":{\"mappings\":{\"properties\":{\"semantic_vector\":{\"type\":\"dense_vector\"}}}}}";
            } else {
                request.set(mapper.readTree(exchange.getRequestBody()));
                response = """
                        {"took":5,"hits":{"total":{"value":1},"hits":[{"_score":1.0,"_source":{
                          "content_id":"content-1","creator_id":"creator","content_type":"ARTICLE",
                          "media_uri":"https://example.com/image.webp","asset_uris":["https://example.com/image.webp"],
                          "title":"title","summary":"summary","tags":["生活"],"profile_version":1,
                          "published_at":"2026-10-31T00:00:00Z"}}]}}
                        """;
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        public void close() { server.stop(0); }
    }
}
