package io.seekflux.platform.retrieval.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seekflux.ranking.domain.RankingCandidate;
import io.seekflux.ranking.domain.RetrievalSource;
import io.seekflux.recommendation.port.out.RecommendationRetriever;
import io.seekflux.search.port.in.SearchHitView;
import io.seekflux.search.port.in.SearchQuery;
import io.seekflux.search.port.in.SearchResultPage;
import io.seekflux.search.port.out.SearchDocument;
import io.seekflux.search.port.out.SearchIndex;
import io.seekflux.search.port.out.SearchRetriever;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public final class ElasticsearchSearchAdapter implements SearchIndex, SearchRetriever, RecommendationRetriever {

    private final WebClient client;
    private final ObjectMapper objectMapper;
    private final String indexName;
    private final Mono<Void> indexReady;

    public ElasticsearchSearchAdapter(
            WebClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${spring.elasticsearch.uris:http://localhost:9200}") String elasticsearchUris,
            @Value("${seekflux.search.index:seekflux-content-v1}") String indexName) {
        String baseUrl = elasticsearchUris.split(",")[0].trim();
        this.client = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.indexName = indexName;
        this.indexReady = ensureIndex().cache();
    }

    @Override
    public Mono<Void> upsert(SearchDocument document) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("content_id", document.contentId());
        body.put("creator_id", document.creatorId());
        body.put("media_uri", document.mediaUri());
        body.put("title", document.title());
        body.put("description", document.description());
        body.put("summary", document.summary());
        body.set("tags", objectMapper.valueToTree(document.tags()));
        body.put("transcript", document.transcript());
        body.put("profile_version", document.profileVersion());
        body.put("published_at", document.publishedAt().toString());
        body.put("searchable", String.join(" ", List.of(
                document.title(), document.description(), document.summary(),
                String.join(" ", document.tags()), document.transcript())));

        return indexReady.then(client.put()
                .uri("/{index}/_doc/{id}?refresh=wait_for", indexName, document.contentId())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(this::requireSuccess)
                .then());
    }

    @Override
    public Mono<Void> delete(String contentId) {
        return indexReady.then(client.delete()
                .uri("/{index}/_doc/{id}?refresh=wait_for", indexName, contentId)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful() || response.statusCode().value() == 404) {
                        return response.releaseBody();
                    }
                    return error(response);
                }));
    }

    @Override
    public Mono<SearchResultPage> search(SearchQuery query) {
        ObjectNode body = searchBody(query);
        long startedAt = System.nanoTime();
        return indexReady.then(client.post()
                .uri("/{index}/_search", indexName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(JsonNode.class)
                        : error(response).then(Mono.empty())))
                .map(response -> mapResults(query, response,
                        (System.nanoTime() - startedAt) / 1_000_000));
    }

    @Override
    public Mono<List<RankingCandidate>> trending(int limit) {
        ObjectNode root = recommendationBody(limit);
        root.putObject("query").putObject("match_all");
        ArrayNode sort = root.putArray("sort");
        sort.addObject().put("published_at", "desc");
        sort.addObject().put("content_id", "asc");
        return searchCandidates(root, RetrievalSource.TRENDING);
    }

    @Override
    public Mono<List<RankingCandidate>> byInterests(List<String> topics, int limit) {
        if (topics == null || topics.isEmpty()) {
            return Mono.just(List.of());
        }
        ObjectNode root = recommendationBody(limit);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode should = bool.putArray("should");
        ObjectNode terms = should.addObject().putObject("terms");
        terms.set("tags", objectMapper.valueToTree(topics));
        terms.put("boost", 5.0);
        ObjectNode multiMatch = should.addObject().putObject("multi_match");
        multiMatch.put("query", String.join(" ", topics));
        multiMatch.put("type", "best_fields");
        multiMatch.put("fuzziness", "AUTO");
        ArrayNode fields = multiMatch.putArray("fields");
        List.of("tags^5", "title^3", "summary^2", "description", "transcript")
                .forEach(fields::add);
        bool.put("minimum_should_match", 1);
        ArrayNode sort = root.putArray("sort");
        sort.addObject().put("_score", "desc");
        sort.addObject().put("published_at", "desc");
        return searchCandidates(root, RetrievalSource.INTEREST);
    }

    @Override
    public Mono<List<RankingCandidate>> similarTo(String contentId, int limit) {
        return indexReady.then(client.get()
                .uri("/{index}/_doc/{id}", indexName, contentId)
                .exchangeToMono(response -> {
                    if (response.statusCode().value() == 404) {
                        return response.releaseBody().thenReturn(objectMapper.missingNode());
                    }
                    return response.statusCode().is2xxSuccessful()
                            ? response.bodyToMono(JsonNode.class)
                            : error(response).then(Mono.empty());
                }))
                .flatMap(seed -> {
                    if (seed.isMissingNode() || !seed.path("found").asBoolean(true)) {
                        return Mono.just(List.of());
                    }
                    return searchCandidates(
                            similarBody(seed.path("_source"), contentId, limit),
                            RetrievalSource.SIMILAR);
                });
    }

    private Mono<Void> ensureIndex() {
        return client.head().uri("/{index}", indexName).exchangeToMono(response -> {
            if (response.statusCode().is2xxSuccessful()) {
                return response.releaseBody();
            }
            if (response.statusCode().value() != 404) {
                return error(response);
            }
            return response.releaseBody().then(createIndex());
        });
    }

    private Mono<Void> createIndex() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode properties = root.putObject("mappings").putObject("properties");
        keyword(properties, "content_id");
        keyword(properties, "creator_id");
        keyword(properties, "media_uri");
        text(properties, "title");
        text(properties, "description");
        text(properties, "summary");
        properties.putObject("tags").put("type", "keyword");
        text(properties, "transcript");
        properties.putObject("profile_version").put("type", "integer");
        properties.putObject("published_at").put("type", "date");
        ObjectNode searchable = properties.putObject("searchable");
        searchable.put("type", "text");
        searchable.putObject("fields").putObject("raw")
                .put("type", "keyword").put("ignore_above", 8192);

        return client.put().uri("/{index}", indexName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(root)
                .exchangeToMono(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.releaseBody();
                    }
                    return response.bodyToMono(String.class).flatMap(body -> {
                        if (response.statusCode().value() == 400
                                && body.contains("resource_already_exists_exception")) {
                            return Mono.empty();
                        }
                        return Mono.error(new IllegalStateException(
                                "Elasticsearch index creation failed: " + response.statusCode() + " " + body));
                    });
                });
    }

    private ObjectNode searchBody(SearchQuery query) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("from", query.page() * query.size());
        root.put("size", query.size());
        root.put("track_total_hits", true);
        ArrayNode source = root.putArray("_source");
        List.of("content_id", "creator_id", "media_uri", "title", "description", "summary",
                "tags", "profile_version", "published_at").forEach(source::add);

        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode should = bool.putArray("should");
        ObjectNode multiMatch = should.addObject().putObject("multi_match");
        multiMatch.put("query", query.text());
        multiMatch.put("type", "best_fields");
        multiMatch.put("fuzziness", "AUTO");
        ArrayNode fields = multiMatch.putArray("fields");
        List.of("title^5", "tags^4", "summary^3", "description^2", "transcript")
                .forEach(fields::add);

        for (String token : queryTokens(query.text())) {
            ObjectNode wildcard = should.addObject().putObject("wildcard").putObject("searchable.raw");
            wildcard.put("value", "*" + escapeWildcard(token) + "*");
            wildcard.put("case_insensitive", true);
            wildcard.put("boost", token.length() > 1 ? 2.0 : 0.2);
        }
        bool.put("minimum_should_match", 1);
        ArrayNode sort = root.putArray("sort");
        sort.addObject().put("_score", "desc");
        sort.addObject().put("published_at", "desc");
        return root;
    }

    private ObjectNode recommendationBody(int limit) {
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("recommendation retrieval limit must be between 1 and 500");
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("size", limit);
        sourceFields(root);
        return root;
    }

    private ObjectNode similarBody(JsonNode seed, String contentId, int limit) {
        ObjectNode root = recommendationBody(limit);
        ObjectNode bool = root.putObject("query").putObject("bool");
        ArrayNode should = bool.putArray("should");

        List<String> tags = new ArrayList<>();
        seed.path("tags").forEach(tag -> tags.add(tag.asText()));
        if (!tags.isEmpty()) {
            ObjectNode terms = should.addObject().putObject("terms");
            terms.set("tags", objectMapper.valueToTree(tags));
            terms.put("boost", 6.0);
        }

        String seedText = String.join(" ", List.of(
                seed.path("title").asText(""), seed.path("summary").asText("")));
        if (!seedText.isBlank()) {
            ObjectNode multiMatch = should.addObject().putObject("multi_match");
            multiMatch.put("query", seedText);
            multiMatch.put("type", "best_fields");
            multiMatch.put("fuzziness", "AUTO");
            ArrayNode fields = multiMatch.putArray("fields");
            List.of("title^3", "tags^3", "summary^2", "description", "transcript")
                    .forEach(fields::add);
        }

        ObjectNode moreLikeThis = should.addObject().putObject("more_like_this");
        ArrayNode mltFields = moreLikeThis.putArray("fields");
        List.of("title", "description", "summary", "transcript").forEach(mltFields::add);
        ObjectNode likeDocument = moreLikeThis.putArray("like").addObject();
        likeDocument.put("_index", indexName);
        likeDocument.put("_id", contentId);
        moreLikeThis.put("min_term_freq", 1);
        moreLikeThis.put("min_doc_freq", 1);
        moreLikeThis.put("max_query_terms", 25);
        bool.put("minimum_should_match", 1);
        bool.putArray("must_not").addObject().putObject("term").put("content_id", contentId);
        ArrayNode sort = root.putArray("sort");
        sort.addObject().put("_score", "desc");
        sort.addObject().put("published_at", "desc");
        return root;
    }

    private Mono<List<RankingCandidate>> searchCandidates(ObjectNode body, RetrievalSource source) {
        return indexReady.then(client.post()
                .uri("/{index}/_search", indexName)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchangeToMono(response -> response.statusCode().is2xxSuccessful()
                        ? response.bodyToMono(JsonNode.class)
                        : error(response).then(Mono.empty())))
                .map(response -> mapCandidates(response, source));
    }

    private List<RankingCandidate> mapCandidates(JsonNode response, RetrievalSource retrievalSource) {
        List<RankingCandidate> candidates = new ArrayList<>();
        int rank = 1;
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            List<String> tags = new ArrayList<>();
            source.path("tags").forEach(tag -> tags.add(tag.asText()));
            candidates.add(new RankingCandidate(
                    source.path("content_id").asText(),
                    source.path("creator_id").asText(),
                    source.path("media_uri").asText(),
                    source.path("title").asText(),
                    source.path("description").asText(""),
                    source.path("summary").asText(),
                    tags,
                    source.path("profile_version").asInt(),
                    Instant.parse(source.path("published_at").asText()),
                    retrievalSource,
                    rank++,
                    hit.path("_score").asDouble(0.0)));
        }
        return List.copyOf(candidates);
    }

    private static void sourceFields(ObjectNode root) {
        ArrayNode source = root.putArray("_source");
        List.of("content_id", "creator_id", "media_uri", "title", "description", "summary",
                "tags", "profile_version", "published_at").forEach(source::add);
    }

    private SearchResultPage mapResults(SearchQuery query, JsonNode response, long measuredMillis) {
        JsonNode hitsNode = response.path("hits");
        long total = hitsNode.path("total").path("value").asLong();
        List<SearchHitView> hits = new ArrayList<>();
        for (JsonNode hit : hitsNode.path("hits")) {
            JsonNode source = hit.path("_source");
            List<String> tags = new ArrayList<>();
            source.path("tags").forEach(tag -> tags.add(tag.asText()));
            hits.add(new SearchHitView(
                    source.path("content_id").asText(),
                    source.path("creator_id").asText(),
                    source.path("media_uri").asText(),
                    source.path("title").asText(),
                    source.path("description").asText(),
                    source.path("summary").asText(),
                    tags,
                    source.path("profile_version").asInt(),
                    hit.path("_score").asDouble(),
                    Instant.parse(source.path("published_at").asText())));
        }
        long took = response.path("took").asLong(measuredMillis);
        return new SearchResultPage(query.text(), total, query.page(), query.size(), took, hits);
    }

    private static Set<String> queryTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
            if (containsCjk(token) && token.length() > 2) {
                for (int index = 0; index < token.length() - 1 && tokens.size() < 24; index++) {
                    tokens.add(token.substring(index, index + 2));
                }
            }
        }
        return tokens;
    }

    private static boolean containsCjk(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static String escapeWildcard(String value) {
        return value.replace("\\", "\\\\").replace("*", "\\*").replace("?", "\\?");
    }

    private static void keyword(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "keyword");
    }

    private static void text(ObjectNode properties, String name) {
        properties.putObject(name).put("type", "text");
    }

    private Mono<Void> requireSuccess(ClientResponse response) {
        return response.statusCode().is2xxSuccessful() ? response.releaseBody() : error(response);
    }

    private Mono<Void> error(ClientResponse response) {
        return response.bodyToMono(String.class).defaultIfEmpty("").flatMap(body -> Mono.error(
                new IllegalStateException("Elasticsearch request failed: "
                        + response.statusCode() + " " + body)));
    }
}
