package io.seekflux.platform.persistence.feature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.feature.application.RealtimeFeaturePolicy;
import io.seekflux.feature.application.FeatureTopics;
import io.seekflux.feature.domain.ContentHeatSnapshot;
import io.seekflux.feature.domain.FeatureProjectionDisposition;
import io.seekflux.feature.domain.FeatureProjectionResult;
import io.seekflux.feature.domain.RealtimeFeatureEvent;
import io.seekflux.feature.domain.ShortTermInterestSnapshot;
import io.seekflux.feature.port.out.RealtimeFeatureProjectionRepository;
import io.seekflux.interaction.domain.InteractionType;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcRealtimeFeatureProjector implements RealtimeFeatureProjectionRepository {

    private static final String STREAM_KEY = "interaction-features-v1";

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;
    private final RealtimeFeaturePolicy policy;
    private final Clock clock;

    @Autowired
    public JdbcRealtimeFeatureProjector(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this(jdbcClient, objectMapper, new RealtimeFeaturePolicy(), Clock.systemUTC());
    }

    JdbcRealtimeFeatureProjector(
            JdbcClient jdbcClient,
            ObjectMapper objectMapper,
            RealtimeFeaturePolicy policy,
            Clock clock) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    @Transactional
    public FeatureProjectionResult project(RealtimeFeatureEvent event) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO feature.realtime_events (
                            event_id, user_id, event_type, content_id, content_tags,
                            event_time, ingested_at, disposition
                        ) VALUES (
                            :eventId, :userId, :eventType, :contentId, CAST(:contentTags AS jsonb),
                            :eventTime, :ingestedAt, 'RECEIVED'
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", event.eventId())
                .param("userId", event.userId())
                .param("eventType", event.eventType().name())
                .param("contentId", event.contentId())
                .param("contentTags", toJson(event.contentTags()))
                .param("eventTime", databaseTime(event.eventTime()))
                .param("ingestedAt", databaseTime(event.ingestedAt()))
                .update();

        Instant currentMax = lockWatermark(event.eventTime());
        Instant currentWatermark = policy.watermark(currentMax);
        if (inserted == 0) {
            return new FeatureProjectionResult(
                    event.eventId(), FeatureProjectionDisposition.DUPLICATE, currentWatermark,
                    "EVENT_ALREADY_PROJECTED");
        }
        if (policy.tooLate(event.eventTime(), currentMax)) {
            jdbcClient.sql("""
                            UPDATE feature.realtime_events
                            SET disposition = 'LATE_DROPPED', rejection_reason = 'BEYOND_ALLOWED_LATENESS'
                            WHERE event_id = :eventId
                            """)
                    .param("eventId", event.eventId())
                    .update();
            return new FeatureProjectionResult(
                    event.eventId(), FeatureProjectionDisposition.LATE_DROPPED,
                    currentWatermark, "BEYOND_ALLOWED_LATENESS");
        }

        Instant windowEnd = event.eventTime().isAfter(currentMax) ? event.eventTime() : currentMax;
        Instant watermark = policy.watermark(windowEnd);
        jdbcClient.sql("""
                        UPDATE feature.stream_watermarks
                        SET max_event_time = :maxEventTime, watermark = :watermark, updated_at = :updatedAt
                        WHERE stream_key = :streamKey
                        """)
                .param("maxEventTime", databaseTime(windowEnd))
                .param("watermark", databaseTime(watermark))
                .param("updatedAt", databaseTime(clock.instant()))
                .param("streamKey", STREAM_KEY)
                .update();
        jdbcClient.sql("""
                        UPDATE feature.realtime_events
                        SET disposition = 'APPLIED', rejection_reason = NULL
                        WHERE event_id = :eventId
                        """)
                .param("eventId", event.eventId())
                .update();

        Instant computedAt = clock.instant();
        ShortTermInterestSnapshot interest = policy.shortTermInterest(
                event.userId(), loadUserEvents(event.userId(), windowEnd), windowEnd, computedAt);
        ContentHeatSnapshot heat = policy.contentHeat(
                event.contentId(), loadContentEvents(event.contentId(), windowEnd), windowEnd, computedAt);
        saveInterest(event.eventId(), interest);
        saveHeat(event.eventId(), heat);
        insertSnapshotOutbox(event.eventId(), interest);
        insertSnapshotOutbox(event.eventId(), heat);
        return new FeatureProjectionResult(event.eventId(), FeatureProjectionDisposition.APPLIED, watermark, null);
    }

    private Instant lockWatermark(Instant initialEventTime) {
        Instant initialWatermark = policy.watermark(initialEventTime);
        jdbcClient.sql("""
                        INSERT INTO feature.stream_watermarks (
                            stream_key, max_event_time, watermark, updated_at
                        ) VALUES (:streamKey, :maxEventTime, :watermark, :updatedAt)
                        ON CONFLICT (stream_key) DO NOTHING
                        """)
                .param("streamKey", STREAM_KEY)
                .param("maxEventTime", databaseTime(initialEventTime))
                .param("watermark", databaseTime(initialWatermark))
                .param("updatedAt", databaseTime(clock.instant()))
                .update();
        return jdbcClient.sql("""
                        SELECT max_event_time
                        FROM feature.stream_watermarks
                        WHERE stream_key = :streamKey
                        FOR UPDATE
                        """)
                .param("streamKey", STREAM_KEY)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private List<RealtimeFeatureEvent> loadUserEvents(String userId, Instant windowEnd) {
        return jdbcClient.sql("""
                        SELECT event_id, user_id, event_type, content_id,
                               content_tags::text AS content_tags, event_time, ingested_at
                        FROM feature.realtime_events
                        WHERE user_id = :userId
                          AND disposition = 'APPLIED'
                          AND event_time BETWEEN :windowStart AND :windowEnd
                        ORDER BY event_time, event_id
                        """)
                .param("userId", userId)
                .param("windowStart", databaseTime(windowEnd.minus(RealtimeFeaturePolicy.SHORT_INTEREST_WINDOW)))
                .param("windowEnd", databaseTime(windowEnd))
                .query((row, number) -> mapEvent(
                        row.getObject("event_id", UUID.class),
                        row.getString("user_id"),
                        row.getString("event_type"),
                        row.getObject("content_id", UUID.class),
                        row.getString("content_tags"),
                        row.getObject("event_time", OffsetDateTime.class).toInstant(),
                        row.getObject("ingested_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private List<RealtimeFeatureEvent> loadContentEvents(UUID contentId, Instant windowEnd) {
        return jdbcClient.sql("""
                        SELECT event_id, user_id, event_type, content_id,
                               content_tags::text AS content_tags, event_time, ingested_at
                        FROM feature.realtime_events
                        WHERE content_id = :contentId
                          AND disposition = 'APPLIED'
                          AND event_time BETWEEN :windowStart AND :windowEnd
                        ORDER BY event_time, event_id
                        """)
                .param("contentId", contentId)
                .param("windowStart", databaseTime(windowEnd.minus(RealtimeFeaturePolicy.CONTENT_HEAT_WINDOW)))
                .param("windowEnd", databaseTime(windowEnd))
                .query((row, number) -> mapEvent(
                        row.getObject("event_id", UUID.class),
                        row.getString("user_id"),
                        row.getString("event_type"),
                        row.getObject("content_id", UUID.class),
                        row.getString("content_tags"),
                        row.getObject("event_time", OffsetDateTime.class).toInstant(),
                        row.getObject("ingested_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private RealtimeFeatureEvent mapEvent(
            UUID eventId,
            String userId,
            String eventType,
            UUID contentId,
            String contentTags,
            Instant eventTime,
            Instant ingestedAt) {
        try {
            List<String> tags = objectMapper.readValue(contentTags, new TypeReference<>() { });
            return new RealtimeFeatureEvent(
                    eventId, userId, InteractionType.valueOf(eventType), contentId,
                    tags, eventTime, ingestedAt);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("stored feature event tags are invalid", invalid);
        }
    }

    private void saveInterest(UUID sourceEventId, ShortTermInterestSnapshot snapshot) {
        jdbcClient.sql("""
                        INSERT INTO feature.short_term_interest_snapshots (
                            user_id, topics, window_start, window_end, computed_at,
                            feature_version, source_event_id
                        ) VALUES (
                            :userId, CAST(:topics AS jsonb), :windowStart, :windowEnd, :computedAt,
                            :featureVersion, :sourceEventId
                        )
                        ON CONFLICT (user_id) DO UPDATE SET
                            topics = EXCLUDED.topics,
                            window_start = EXCLUDED.window_start,
                            window_end = EXCLUDED.window_end,
                            computed_at = EXCLUDED.computed_at,
                            feature_version = EXCLUDED.feature_version,
                            source_event_id = EXCLUDED.source_event_id
                        WHERE EXCLUDED.window_end >= feature.short_term_interest_snapshots.window_end
                        """)
                .param("userId", snapshot.userId())
                .param("topics", toJson(snapshot.topics()))
                .param("windowStart", databaseTime(snapshot.windowStart()))
                .param("windowEnd", databaseTime(snapshot.windowEnd()))
                .param("computedAt", databaseTime(snapshot.computedAt()))
                .param("featureVersion", snapshot.featureVersion())
                .param("sourceEventId", sourceEventId)
                .update();
    }

    private void saveHeat(UUID sourceEventId, ContentHeatSnapshot snapshot) {
        jdbcClient.sql("""
                        INSERT INTO feature.content_heat_snapshots (
                            content_id, score, event_count, window_start, window_end,
                            computed_at, feature_version, source_event_id
                        ) VALUES (
                            :contentId, :score, :eventCount, :windowStart, :windowEnd,
                            :computedAt, :featureVersion, :sourceEventId
                        )
                        ON CONFLICT (content_id) DO UPDATE SET
                            score = EXCLUDED.score,
                            event_count = EXCLUDED.event_count,
                            window_start = EXCLUDED.window_start,
                            window_end = EXCLUDED.window_end,
                            computed_at = EXCLUDED.computed_at,
                            feature_version = EXCLUDED.feature_version,
                            source_event_id = EXCLUDED.source_event_id
                        WHERE EXCLUDED.window_end >= feature.content_heat_snapshots.window_end
                        """)
                .param("contentId", snapshot.contentId())
                .param("score", snapshot.score())
                .param("eventCount", snapshot.eventCount())
                .param("windowStart", databaseTime(snapshot.windowStart()))
                .param("windowEnd", databaseTime(snapshot.windowEnd()))
                .param("computedAt", databaseTime(snapshot.computedAt()))
                .param("featureVersion", snapshot.featureVersion())
                .param("sourceEventId", sourceEventId)
                .update();
    }

    private void insertSnapshotOutbox(UUID sourceEventId, ShortTermInterestSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshot_type", "SHORT_TERM_INTEREST");
        payload.put("entity_id", snapshot.userId());
        payload.put("topics", snapshot.topics());
        putWindow(payload, snapshot.windowStart(), snapshot.windowEnd(), snapshot.computedAt(), snapshot.featureVersion());
        insertOutbox(derivedId(sourceEventId, "short-interest"), snapshot.userId(), snapshot.computedAt(), payload);
    }

    private void insertSnapshotOutbox(UUID sourceEventId, ContentHeatSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshot_type", "CONTENT_HEAT");
        payload.put("entity_id", snapshot.contentId().toString());
        payload.put("score", snapshot.score());
        payload.put("event_count", snapshot.eventCount());
        putWindow(payload, snapshot.windowStart(), snapshot.windowEnd(), snapshot.computedAt(), snapshot.featureVersion());
        insertOutbox(derivedId(sourceEventId, "content-heat"), snapshot.contentId().toString(), snapshot.computedAt(), payload);
    }

    private static void putWindow(
            Map<String, Object> payload,
            Instant windowStart,
            Instant windowEnd,
            Instant computedAt,
            String featureVersion) {
        payload.put("window_start", windowStart.toString());
        payload.put("window_end", windowEnd.toString());
        payload.put("computed_at", computedAt.toString());
        payload.put("feature_version", featureVersion);
    }

    private void insertOutbox(UUID eventId, String aggregateId, Instant eventTime, Map<String, Object> payload) {
        jdbcClient.sql("""
                        INSERT INTO outbox.events (
                            event_id, aggregate_type, aggregate_id, event_type,
                            schema_version, event_time, payload
                        ) VALUES (
                            :eventId, 'RealtimeFeatureSnapshot', :aggregateId, :eventType,
                            1, :eventTime, CAST(:payload AS jsonb)
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("aggregateId", aggregateId)
                .param("eventType", FeatureTopics.SNAPSHOT_UPDATED)
                .param("eventTime", databaseTime(eventTime))
                .param("payload", toJson(payload))
                .update();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("failed to serialize realtime feature value", invalid);
        }
    }

    private static UUID derivedId(UUID sourceEventId, String suffix) {
        return UUID.nameUUIDFromBytes(
                (sourceEventId + ":" + suffix + ":" + RealtimeFeaturePolicy.FEATURE_VERSION)
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }
}
