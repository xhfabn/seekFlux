package io.seekflux.pipelines.realtimefeatures;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.feature.application.FeatureTopics;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.flink.api.common.functions.MapFunction;

public final class FeatureEnvelopeMapper {

    private FeatureEnvelopeMapper() {
    }

    public static final class Interest implements MapFunction<ShortTermInterestSnapshot, String> {
        @Override
        public String map(ShortTermInterestSnapshot snapshot) throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshot_type", "SHORT_TERM_INTEREST");
            payload.put("entity_id", snapshot.userId());
            payload.put("topics", snapshot.topics());
            putWindow(payload, snapshot.windowStart(), snapshot.windowEnd(), snapshot.computedAt(), snapshot.featureVersion());
            return envelope(snapshot.userId(), snapshot.windowEnd(), payload);
        }
    }

    public static final class Heat implements MapFunction<ContentHeatSnapshot, String> {
        @Override
        public String map(ContentHeatSnapshot snapshot) throws Exception {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("snapshot_type", "CONTENT_HEAT");
            payload.put("entity_id", snapshot.contentId().toString());
            payload.put("score", snapshot.score());
            payload.put("event_count", snapshot.eventCount());
            putWindow(payload, snapshot.windowStart(), snapshot.windowEnd(), snapshot.computedAt(), snapshot.featureVersion());
            return envelope(snapshot.contentId().toString(), snapshot.windowEnd(), payload);
        }
    }

    public static final class Late implements MapFunction<RealtimeFeatureEvent, String> {
        @Override
        public String map(RealtimeFeatureEvent event) throws Exception {
            Map<String, Object> payload = Map.of(
                    "event_id", event.eventId().toString(),
                    "user_id", event.userId(),
                    "content_id", event.contentId().toString(),
                    "event_time", event.eventTime().toString(),
                    "reason", "BEYOND_ALLOWED_LATENESS");
            return rawEnvelope(
                    event.eventId(), FeatureTopics.LATE_EVENT, event.eventTime(), payload);
        }
    }

    private static void putWindow(
            Map<String, Object> payload,
            Instant start,
            Instant end,
            Instant computedAt,
            String version) {
        payload.put("window_start", start.toString());
        payload.put("window_end", end.toString());
        payload.put("computed_at", computedAt.toString());
        payload.put("feature_version", version);
    }

    private static String envelope(String entityId, Instant windowEnd, Map<String, Object> payload) throws Exception {
        UUID eventId = UUID.nameUUIDFromBytes(
                (entityId + ":" + windowEnd + ":" + payload.get("snapshot_type"))
                        .getBytes(StandardCharsets.UTF_8));
        return rawEnvelope(eventId, FeatureTopics.SNAPSHOT_UPDATED, windowEnd, payload);
    }

    private static String rawEnvelope(
            UUID eventId,
            String eventType,
            Instant eventTime,
            Map<String, Object> payload) throws Exception {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", eventId.toString());
        envelope.put("event_type", eventType);
        envelope.put("schema_version", 1);
        envelope.put("event_time", eventTime.toString());
        envelope.put("ingested_at", Instant.now().toString());
        envelope.put("producer", "seekflux-flink-realtime-features");
        envelope.put("payload", payload);
        return new ObjectMapper().writeValueAsString(envelope);
    }
}
