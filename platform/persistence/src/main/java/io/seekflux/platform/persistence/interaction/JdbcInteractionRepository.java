package io.seekflux.platform.persistence.interaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.interaction.application.InteractionIdempotencyConflictException;
import io.seekflux.interaction.application.InteractionTopics;
import io.seekflux.interaction.domain.InteractionDisposition;
import io.seekflux.interaction.domain.InteractionSignal;
import io.seekflux.interaction.domain.InteractionType;
import io.seekflux.interaction.port.in.InteractionBatchReceipt;
import io.seekflux.interaction.port.in.InteractionEventReceipt;
import io.seekflux.interaction.port.out.InteractionBatch;
import io.seekflux.interaction.port.out.InteractionRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcInteractionRepository implements InteractionRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcInteractionRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public InteractionBatchReceipt ingest(InteractionBatch batch) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO interaction.batches (
                            batch_id, idempotency_key, request_hash, user_id, received_at
                        ) VALUES (
                            :batchId, :idempotencyKey, :requestHash, :userId, :receivedAt
                        )
                        ON CONFLICT (idempotency_key) DO NOTHING
                        """)
                .param("batchId", batch.batchId())
                .param("idempotencyKey", batch.idempotencyKey())
                .param("requestHash", batch.requestHash())
                .param("userId", batch.userId())
                .param("receivedAt", databaseTime(batch.receivedAt()))
                .update();
        if (inserted == 0) {
            return replayExisting(batch);
        }

        Map<UUID, InteractionEventReceipt> receipts = new HashMap<>();
        List<InteractionSignal> processingOrder = new ArrayList<>(batch.events());
        processingOrder.sort(Comparator
                .comparing((InteractionSignal event) -> event.eventType() == InteractionType.EXPOSURE ? 0 : 1)
                .thenComparing(InteractionSignal::eventTime));
        for (InteractionSignal event : processingOrder) {
            receipts.put(event.eventId(), ingestEvent(batch, event));
        }

        List<InteractionEventReceipt> ordered = batch.events().stream()
                .map(event -> receipts.get(event.eventId()))
                .toList();
        InteractionBatchReceipt receipt = receipt(batch.batchId(), ordered);
        jdbcClient.sql("""
                        UPDATE interaction.batches
                        SET response = CAST(:response AS jsonb)
                        WHERE batch_id = :batchId
                        """)
                .param("response", toJson(receipt))
                .param("batchId", batch.batchId())
                .update();
        return receipt;
    }

    private InteractionBatchReceipt replayExisting(InteractionBatch batch) {
        ExistingBatch existing = jdbcClient.sql("""
                        SELECT request_hash, response::text AS response
                        FROM interaction.batches
                        WHERE idempotency_key = :idempotencyKey
                        FOR UPDATE
                        """)
                .param("idempotencyKey", batch.idempotencyKey())
                .query((row, rowNumber) -> new ExistingBatch(
                        row.getString("request_hash"), row.getString("response")))
                .single();
        if (!existing.requestHash().equals(batch.requestHash())) {
            throw new InteractionIdempotencyConflictException(batch.idempotencyKey());
        }
        if (existing.response() == null) {
            throw new IllegalStateException("interaction batch receipt is not yet available");
        }
        try {
            return objectMapper.readValue(existing.response(), InteractionBatchReceipt.class).asReplay();
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("failed to deserialize interaction batch receipt", invalid);
        }
    }

    private InteractionEventReceipt ingestEvent(InteractionBatch batch, InteractionSignal event) {
        if (exists(event.eventId())) {
            return new InteractionEventReceipt(event.eventId(), InteractionDisposition.DUPLICATE, "EVENT_ALREADY_RECORDED");
        }
        Optional<ContentEligibility> content = jdbcClient.sql("""
                        SELECT status, COALESCE(profile_tags, source_tags, '[]'::jsonb)::text AS tags
                        FROM content.contents
                        WHERE content_id = :contentId
                        """)
                .param("contentId", event.contentId())
                .query((row, rowNumber) -> new ContentEligibility(
                        row.getString("status"), parseTags(row.getString("tags"))))
                .optional();
        if (content.isEmpty()) {
            return reject(batch, event, "CONTENT_NOT_FOUND");
        }
        if (!"PUBLISHED".equals(content.get().status())) {
            return reject(batch, event, "CONTENT_NOT_PUBLISHED");
        }
        if (event.eventType().requiresExposure()) {
            AttributionStatus attribution = attributionStatus(batch.userId(), event);
            if (attribution != AttributionStatus.VALID) {
                return reject(batch, event, attribution == AttributionStatus.FUTURE_ONLY
                        ? "EVENT_BEFORE_EXPOSURE"
                        : "ATTRIBUTION_NOT_FOUND");
            }
        }
        if (!insertIngress(batch, event, InteractionDisposition.ACCEPTED, null)) {
            return new InteractionEventReceipt(event.eventId(), InteractionDisposition.DUPLICATE, "EVENT_ALREADY_RECORDED");
        }
        insertOutbox(batch.userId(), event, content.get().tags());
        return new InteractionEventReceipt(event.eventId(), InteractionDisposition.ACCEPTED, null);
    }

    private InteractionEventReceipt reject(InteractionBatch batch, InteractionSignal event, String reason) {
        if (!insertIngress(batch, event, InteractionDisposition.REJECTED, reason)) {
            return new InteractionEventReceipt(event.eventId(), InteractionDisposition.DUPLICATE, "EVENT_ALREADY_RECORDED");
        }
        return new InteractionEventReceipt(event.eventId(), InteractionDisposition.REJECTED, reason);
    }

    private boolean insertIngress(
            InteractionBatch batch,
            InteractionSignal event,
            InteractionDisposition disposition,
            String reason) {
        return jdbcClient.sql("""
                        INSERT INTO interaction.ingress_events (
                            event_id, batch_id, user_id, event_type, request_id, trace_id,
                            content_id, position, surface, event_time, disposition,
                            rejection_reason, received_at
                        ) VALUES (
                            :eventId, :batchId, :userId, :eventType, :requestId, :traceId,
                            :contentId, :position, :surface, :eventTime, :disposition,
                            :rejectionReason, :receivedAt
                        )
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("eventId", event.eventId())
                .param("batchId", batch.batchId())
                .param("userId", batch.userId())
                .param("eventType", event.eventType().name())
                .param("requestId", event.requestId())
                .param("traceId", event.traceId())
                .param("contentId", event.contentId())
                .param("position", event.position())
                .param("surface", event.surface().name())
                .param("eventTime", databaseTime(event.eventTime()))
                .param("disposition", disposition.name())
                .param("rejectionReason", reason)
                .param("receivedAt", databaseTime(batch.receivedAt()))
                .update() == 1;
    }

    private AttributionStatus attributionStatus(String userId, InteractionSignal event) {
        List<OffsetDateTime> exposures = jdbcClient.sql("""
                        SELECT event_time
                        FROM interaction.ingress_events
                        WHERE user_id = :userId
                          AND request_id = :requestId
                          AND trace_id = :traceId
                          AND content_id = :contentId
                          AND position = :position
                          AND surface = :surface
                          AND event_type = 'EXPOSURE'
                          AND disposition = 'ACCEPTED'
                        """)
                .param("userId", userId)
                .param("requestId", event.requestId())
                .param("traceId", event.traceId())
                .param("contentId", event.contentId())
                .param("position", event.position())
                .param("surface", event.surface().name())
                .query(OffsetDateTime.class)
                .list();
        if (exposures.stream().anyMatch(time -> !time.toInstant().isAfter(event.eventTime()))) {
            return AttributionStatus.VALID;
        }
        return exposures.isEmpty() ? AttributionStatus.MISSING : AttributionStatus.FUTURE_ONLY;
    }

    private boolean exists(UUID eventId) {
        return jdbcClient.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM interaction.ingress_events WHERE event_id = :eventId
                        )
                        """)
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    private void insertOutbox(String userId, InteractionSignal event, List<String> contentTags) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event_id", event.eventId().toString());
        payload.put("user_id", userId);
        payload.put("event_type", event.eventType().name());
        payload.put("request_id", event.requestId());
        payload.put("trace_id", event.traceId());
        payload.put("content_id", event.contentId().toString());
        payload.put("position", event.position());
        payload.put("surface", event.surface().name());
        payload.put("event_time", event.eventTime().toString());
        payload.put("content_tags", contentTags);
        int inserted = jdbcClient.sql("""
                        INSERT INTO outbox.events (
                            event_id, aggregate_type, aggregate_id, event_type,
                            schema_version, event_time, payload
                        ) VALUES (
                            :eventId, 'Interaction', :aggregateId, :eventType,
                            1, :eventTime, CAST(:payload AS jsonb)
                        )
                        """)
                .param("eventId", event.eventId())
                .param("aggregateId", userId)
                .param("eventType", InteractionTopics.forType(event.eventType()))
                .param("eventTime", databaseTime(event.eventTime()))
                .param("payload", toJson(payload))
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("interaction outbox insert did not affect exactly one row");
        }
    }

    private static InteractionBatchReceipt receipt(UUID batchId, List<InteractionEventReceipt> events) {
        int accepted = (int) events.stream()
                .filter(event -> event.disposition() == InteractionDisposition.ACCEPTED)
                .count();
        int duplicate = (int) events.stream()
                .filter(event -> event.disposition() == InteractionDisposition.DUPLICATE)
                .count();
        int rejected = (int) events.stream()
                .filter(event -> event.disposition() == InteractionDisposition.REJECTED)
                .count();
        return new InteractionBatchReceipt(batchId, false, accepted, duplicate, rejected, events);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("failed to serialize interaction value", invalid);
        }
    }

    private List<String> parseTags(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("failed to deserialize content tags for interaction", invalid);
        }
    }

    private static OffsetDateTime databaseTime(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private enum AttributionStatus {
        VALID,
        MISSING,
        FUTURE_ONLY
    }

    private record ExistingBatch(String requestHash, String response) {
    }

    private record ContentEligibility(String status, List<String> tags) {
        private ContentEligibility {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
}
