package io.seekflux.apps.workerrunner.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.content.application.ContentApplicationService;
import io.seekflux.content.domain.ContentId;
import io.seekflux.content.domain.ContentStateException;
import io.seekflux.content.domain.ContentStatus;
import io.seekflux.content.port.in.CompleteContentProfileCommand;
import io.seekflux.content.port.in.ContentUseCase;
import java.util.ArrayList;
import java.util.List;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public final class BasicContentProfileWorker {

    private final ContentUseCase contentUseCase;
    private final ObjectMapper objectMapper;

    public BasicContentProfileWorker(ContentUseCase contentUseCase, ObjectMapper objectMapper) {
        this.contentUseCase = contentUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = ContentApplicationService.CONTENT_SUBMITTED)
    public void handle(String envelopeJson) throws Exception {
        JsonNode envelope = objectMapper.readTree(envelopeJson);
        if (!ContentApplicationService.CONTENT_SUBMITTED.equals(envelope.path("event_type").asText())) {
            throw new IllegalArgumentException("unexpected event type for content profile worker");
        }
        JsonNode payload = envelope.path("payload");
        ContentId contentId = ContentId.parse(requiredText(payload, "content_id"));
        if (contentUseCase.get(contentId).status() == ContentStatus.WITHDRAWN) {
            return;
        }
        String title = requiredText(payload, "title");
        String description = payload.path("description").asText("").trim();
        String summary = description.isEmpty() ? title : title + " — " + description;
        if (summary.length() > 4_000) {
            summary = summary.substring(0, 4_000);
        }

        List<String> tags = new ArrayList<>();
        payload.path("source_tags").forEach(tag -> {
            if (tag.isTextual() && !tag.asText().isBlank()) {
                tags.add(tag.asText());
            }
        });

        CompleteContentProfileCommand command = new CompleteContentProfileCommand(
                contentId, 1, summary, tags, "");
        try {
            contentUseCase.completeProfile(command);
            contentUseCase.publish(contentId);
        } catch (ContentStateException error) {
            // A withdrawal can race the asynchronous profile worker. The stale event is
            // already satisfied and must not poison the Kafka partition with retries.
            if (contentUseCase.get(contentId).status() != ContentStatus.WITHDRAWN) {
                throw error;
            }
        }
    }

    private static String requiredText(JsonNode payload, String field) {
        String value = payload.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing content event field: " + field);
        }
        return value;
    }
}
