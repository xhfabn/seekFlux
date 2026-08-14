package io.seekflux.platform.modelserving;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seekflux.search.port.out.MediaEmbeddingBatch;
import io.seekflux.search.port.out.MediaEmbeddingPort;
import io.seekflux.search.port.out.MediaEmbeddingSegment;
import io.seekflux.search.port.out.MediaModality;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(prefix = "seekflux.multimodal", name = "enabled", havingValue = "true")
public final class HttpMultimodalEmbeddingAdapter implements MediaEmbeddingPort {

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public HttpMultimodalEmbeddingAdapter(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${seekflux.multimodal.endpoint:http://127.0.0.1:8090}") String endpoint,
            @Value("${seekflux.multimodal.connect-timeout-ms:1000}") long connectTimeoutMillis,
            @Value("${seekflux.multimodal.read-timeout-ms:30000}") long readTimeoutMillis) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));
        this.client = builder.baseUrl(endpoint).requestFactory(factory).build();
        this.objectMapper = objectMapper;
    }

    @Override
    public MediaEmbeddingBatch embed(MediaModality modality, String input, int maxSegments) {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("modality", modality.name());
        request.put("input", input);
        request.put("maxSegments", maxSegments);
        JsonNode response = client.post()
                .uri("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            throw new IllegalStateException("multimodal model returned an empty response");
        }
        int dimensions = response.path("dimensions").asInt();
        List<MediaEmbeddingSegment> segments = new ArrayList<>();
        for (JsonNode segment : response.path("segments")) {
            List<Double> vector = new ArrayList<>();
            segment.path("vector").forEach(value -> vector.add(value.asDouble()));
            segments.add(new MediaEmbeddingSegment(
                    segment.path("ordinal").asInt(),
                    segment.path("startMillis").asLong(),
                    segment.path("endMillis").asLong(),
                    segment.path("previewUri").asText(""),
                    vector));
        }
        return new MediaEmbeddingBatch(response.path("modelVersion").asText(), dimensions, segments);
    }
}
