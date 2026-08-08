package io.seekflux.apps.workerrunner.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.content.application.ContentApplicationService;
import io.seekflux.search.port.out.SearchDocument;
import io.seekflux.search.port.out.SearchIndex;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public final class ContentSearchIndexWorker {

    private final SearchIndex searchIndex;
    private final ObjectMapper objectMapper;

    public ContentSearchIndexWorker(SearchIndex searchIndex, ObjectMapper objectMapper) {
        this.searchIndex = searchIndex;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = ContentApplicationService.CONTENT_PROFILE_PUBLISHED,
            groupId = "seekflux-content-search-index-v2")
    public void indexPublished(String envelopeJson) throws Exception {
        JsonNode envelope = objectMapper.readTree(envelopeJson);
        JsonNode payload = envelope.path("payload");
        List<String> tags = new ArrayList<>();
        payload.path("tags").forEach(tag -> tags.add(tag.asText()));
        SearchDocument document = new SearchDocument(
                requiredText(payload, "content_id"),
                requiredText(payload, "creator_id"),
                requiredText(payload, "media_uri"),
                requiredText(payload, "title"),
                payload.path("description").asText(""),
                requiredText(payload, "summary"),
                tags,
                payload.path("transcript").asText(""),
                payload.path("profile_version").asInt(),
                Instant.parse(requiredText(envelope, "event_time")));
        searchIndex.upsert(document);
    }

    @KafkaListener(
            topics = ContentApplicationService.CONTENT_DISTRIBUTION_CHANGED,
            groupId = "seekflux-content-search-index-v2")
    public void removeWithdrawn(String envelopeJson) throws Exception {
        JsonNode payload = objectMapper.readTree(envelopeJson).path("payload");
        if ("WITHDRAWN".equals(payload.path("distribution_status").asText())) {
            searchIndex.delete(requiredText(payload, "content_id"));
        }
    }

    private static String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing content search event field: " + field);
        }
        return value;
    }
}
