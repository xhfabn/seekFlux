package io.seekflux.platform.retrieval.elasticsearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seekflux.search.port.out.MediaSearchCandidate;
import io.seekflux.search.port.out.MediaSegmentDocument;
import io.seekflux.search.port.out.MediaSegmentIndex;
import io.seekflux.search.port.out.MediaSegmentRetriever;
import io.seekflux.search.port.out.MediaUnderstandingEvidence;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "seekflux.multimodal", name = "enabled", havingValue = "true")
public final class ElasticsearchMediaSegmentAdapter implements MediaSegmentIndex, MediaSegmentRetriever {

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String indexName;
    private final int dimensions;
    private final Object indexMonitor = new Object();
    private volatile boolean indexReady;

    public ElasticsearchMediaSegmentAdapter(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${spring.elasticsearch.uris:http://localhost:9200}") String elasticsearchUris,
            @Value("${seekflux.multimodal.index:seekflux-media-segments-v2}") String indexName,
            @Value("${seekflux.multimodal.dimensions:768}") int dimensions,
            @Value("${seekflux.retrieval.connect-timeout-ms:1000}") long connectTimeoutMillis,
            @Value("${seekflux.retrieval.read-timeout-ms:2000}") long readTimeoutMillis) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.client = builder.baseUrl(elasticsearchUris.split(",")[0].trim()).requestFactory(factory).build();
        this.objectMapper = objectMapper;
        this.indexName = indexName;
        this.dimensions = dimensions;
    }

    @Override
    public void upsert(MediaSegmentDocument document) {
        if (document.vector().size() != dimensions) {
            throw new IllegalArgumentException("media vector dimensions do not match configured index");
        }
        ensureIndex();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("content_id", document.contentId());
        body.put("content_type", document.contentType());
        body.put("media_uri", document.mediaUri());
        body.set("asset_uris", objectMapper.valueToTree(document.assetUris()));
        body.put("title", document.title());
        body.put("summary", document.summary());
        body.set("tags", objectMapper.valueToTree(document.tags()));
        body.put("segment_ordinal", document.segmentOrdinal());
        body.put("start_ms", document.startMillis());
        body.put("end_ms", document.endMillis());
        body.put("preview_uri", document.previewUri());
        body.put("model_version", document.modelVersion());
        body.set("visual_vector", objectMapper.valueToTree(document.vector()));
        body.put("understanding_text", document.understandingText());
        body.set("evidence", objectMapper.valueToTree(document.evidence()));
        body.set("channel_statuses", objectMapper.valueToTree(document.channelStatuses()));
        body.put("published_at", document.publishedAt().toString());
        client.put()
                .uri("/{index}/_doc/{id}?refresh=wait_for", indexName,
                        document.contentId() + "-" + document.segmentOrdinal())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((request, response) -> {
                    requireSuccess(response);
                    return null;
                });
    }

    @Override
    public void deleteByContentId(String contentId) {
        ensureIndex();
        ObjectNode body = objectMapper.createObjectNode();
        body.putObject("query").putObject("term").put("content_id", contentId);
        client.post().uri("/{index}/_delete_by_query?refresh=true", indexName)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((request, response) -> {
                    requireSuccess(response);
                    return null;
                });
    }

    @Override
    public List<MediaSearchCandidate> retrieve(List<Double> vector, int limit) {
        if (vector.size() != dimensions) {
            throw new IllegalArgumentException("query vector dimensions do not match media index");
        }
        ensureIndex();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", limit);
        ObjectNode knn = body.putObject("knn");
        knn.put("field", "visual_vector");
        knn.set("query_vector", objectMapper.valueToTree(vector));
        knn.put("k", limit);
        knn.put("num_candidates", Math.min(10000, Math.max(100, limit * 10)));
        JsonNode response = client.post().uri("/{index}/_search", indexName)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((request, result) -> responseBody(result));
        return candidates(response, List.of("VISUAL"));
    }

    @Override
    public List<MediaSearchCandidate> retrieveText(String query, int limit) {
        ensureIndex();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("size", limit);
        ObjectNode match = body.putObject("query").putObject("multi_match");
        match.put("query", query);
        ArrayNode fields = match.putArray("fields");
        fields.add("understanding_text^2").add("title^1.5").add("summary").add("tags^1.5");
        JsonNode response = client.post().uri("/{index}/_search", indexName)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((request, result) -> responseBody(result));
        return candidates(response, List.of("UNDERSTANDING_TEXT"));
    }

    private void ensureIndex() {
        if (indexReady) return;
        synchronized (indexMonitor) {
            if (indexReady) return;
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode properties = root.putObject("mappings").putObject("properties");
            for (String field : List.of("content_id", "content_type", "tags", "model_version")) {
                properties.putObject(field).put("type", "keyword");
            }
            for (String field : List.of("media_uri", "asset_uris", "preview_uri")) {
                properties.putObject(field).put("type", "keyword").put("index", false);
            }
            properties.putObject("title").put("type", "text");
            properties.putObject("summary").put("type", "text");
            properties.putObject("understanding_text").put("type", "text");
            properties.putObject("segment_ordinal").put("type", "integer");
            properties.putObject("start_ms").put("type", "long");
            properties.putObject("end_ms").put("type", "long");
            properties.putObject("published_at").put("type", "date");
            ObjectNode vector = properties.putObject("visual_vector");
            vector.put("type", "dense_vector").put("dims", dimensions).put("index", true).put("similarity", "cosine");
            ObjectNode evidence = properties.putObject("evidence").put("type", "nested").putObject("properties");
            evidence.putObject("channel").put("type", "keyword");
            evidence.putObject("text").put("type", "text");
            evidence.putObject("confidence").put("type", "float");
            evidence.putObject("startMillis").put("type", "long");
            evidence.putObject("endMillis").put("type", "long");
            evidence.putObject("modelVersion").put("type", "keyword");
            properties.putObject("channel_statuses").put("type", "object").put("enabled", false);
            client.put().uri("/{index}", indexName).contentType(MediaType.APPLICATION_JSON).body(root)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful() && response.getStatusCode().value() != 400) {
                            throw failure(response);
                        }
                        return null;
                    });
            indexReady = true;
        }
    }

    private List<MediaSearchCandidate> candidates(JsonNode response, List<String> matchedChannels) {
        List<MediaSearchCandidate> candidates = new ArrayList<>();
        for (JsonNode hit : response.path("hits").path("hits")) {
            JsonNode source = hit.path("_source");
            List<String> assets = new ArrayList<>();
            source.path("asset_uris").forEach(value -> assets.add(value.asText()));
            List<String> tags = new ArrayList<>();
            source.path("tags").forEach(value -> tags.add(value.asText()));
            List<MediaUnderstandingEvidence> evidence = new ArrayList<>();
            for (JsonNode item : source.path("evidence")) {
                evidence.add(new MediaUnderstandingEvidence(item.path("channel").asText(),
                        item.path("text").asText(), item.path("confidence").asDouble(),
                        item.path("startMillis").asLong(), item.path("endMillis").asLong(),
                        item.path("modelVersion").asText()));
            }
            candidates.add(new MediaSearchCandidate(
                    source.path("content_id").asText(), source.path("content_type").asText(),
                    source.path("media_uri").asText(), assets, source.path("title").asText(),
                    source.path("summary").asText(), tags, source.path("start_ms").asLong(),
                    source.path("end_ms").asLong(), source.path("preview_uri").asText(),
                    hit.path("_score").asDouble(), source.path("model_version").asText(),
                    matchedChannels, evidence));
        }
        return candidates;
    }

    private static void requireSuccess(ClientHttpResponse response) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) throw failure(response);
    }

    private JsonNode responseBody(ClientHttpResponse response) throws IOException {
        requireSuccess(response);
        return objectMapper.readTree(response.getBody());
    }

    private static IllegalStateException failure(ClientHttpResponse response) throws IOException {
        return new IllegalStateException("Elasticsearch media request failed: " + response.getStatusCode() + " "
                + new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
    }
}
