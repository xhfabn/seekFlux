package io.seekflux.apps.workerrunner.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.content.application.ContentApplicationService;
import io.seekflux.content.domain.ContentId;
import io.seekflux.content.domain.ContentStateException;
import io.seekflux.content.domain.ContentStatus;
import io.seekflux.content.port.in.CompleteContentProfileCommand;
import io.seekflux.content.port.in.ContentUseCase;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public final class BasicContentProfileWorker {

    private static final Pattern HASH_TAG = Pattern.compile("[#＃]([\\p{L}\\p{N}_-]{1,64})");
    private static final Map<String, List<String>> CONTROLLED_TAGS = controlledTags();

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
        String body = payload.path("body").asText("").trim();
        String summaryText = description.isEmpty() ? body : description;
        String summary = summaryText.isEmpty() ? title : title + " — " + summaryText;
        if (summary.length() > 4_000) {
            summary = summary.substring(0, 4_000);
        }

        Set<String> tags = new LinkedHashSet<>();
        payload.path("source_tags").forEach(tag -> {
            if (tag.isTextual() && !tag.asText().isBlank()) {
                tags.add(tag.asText().trim());
            }
        });
        String searchable = String.join(" ", title, description, body);
        Matcher matcher = HASH_TAG.matcher(searchable);
        while (matcher.find()) {
            tags.add(matcher.group(1));
        }
        CONTROLLED_TAGS.forEach((tag, keywords) -> {
            if (keywords.stream().anyMatch(searchable::contains)) {
                tags.add(tag);
            }
        });

        CompleteContentProfileCommand command = new CompleteContentProfileCommand(
                contentId, 1, summary, List.copyOf(tags), "");
        try {
            contentUseCase.completeProfile(command);
            if (!tags.isEmpty()) {
                contentUseCase.publish(contentId);
            }
        } catch (ContentStateException error) {
            // A withdrawal can race the asynchronous profile worker. The stale event is
            // already satisfied and must not poison the Kafka partition with retries.
            if (contentUseCase.get(contentId).status() != ContentStatus.WITHDRAWN) {
                throw error;
            }
        }
    }

    private static Map<String, List<String>> controlledTags() {
        Map<String, List<String>> tags = new LinkedHashMap<>();
        tags.put("露营", List.of("露营", "帐篷", "camping"));
        tags.put("亲子", List.of("亲子", "带娃", "儿童", "家庭"));
        tags.put("咖啡", List.of("咖啡", "手冲", "拿铁", "coffee"));
        tags.put("摄影", List.of("摄影", "拍照", "相机", "photo"));
        tags.put("旅行", List.of("旅行", "旅游", "路线", "目的地", "travel"));
        tags.put("科技", List.of("科技", "人工智能", "AI", "ai", "大模型"));
        tags.put("美食", List.of("美食", "烹饪", "菜谱", "料理", "餐厅"));
        tags.put("生活", List.of("生活", "家居", "日常", "vlog"));
        return Collections.unmodifiableMap(tags);
    }

    private static String requiredText(JsonNode payload, String field) {
        String value = payload.path(field).asText("").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("missing content event field: " + field);
        }
        return value;
    }
}
