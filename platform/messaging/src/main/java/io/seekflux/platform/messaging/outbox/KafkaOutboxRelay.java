package io.seekflux.platform.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class KafkaOutboxRelay {

    private static final Logger logger = LoggerFactory.getLogger(KafkaOutboxRelay.class);

    private final JdbcClient jdbcClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final long sendTimeoutMillis;

    public KafkaOutboxRelay(
            JdbcClient jdbcClient,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${seekflux.outbox.batch-size:50}") int batchSize,
            @Value("${seekflux.outbox.send-timeout-ms:10000}") long sendTimeoutMillis) {
        this.jdbcClient = jdbcClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
        this.sendTimeoutMillis = sendTimeoutMillis;
    }

    @Scheduled(fixedDelayString = "${seekflux.outbox.relay-delay-ms:1000}")
    public void relay() {
        try {
            for (OutboxRecord record : claimBatch()) {
                publish(record);
            }
        } catch (RuntimeException error) {
            logger.error("Outbox relay iteration failed", error);
        }
    }

    List<OutboxRecord> claimBatch() {
        return jdbcClient.sql("""
                        WITH candidates AS (
                            SELECT event_id
                            FROM outbox.events
                            WHERE (status = 'PENDING' AND next_attempt_at <= now())
                               OR (status = 'PUBLISHING' AND locked_at < now() - interval '2 minutes')
                            ORDER BY created_at
                            LIMIT :batchSize
                            FOR UPDATE SKIP LOCKED
                        )
                        UPDATE outbox.events event
                        SET status = 'PUBLISHING', locked_at = now()
                        FROM candidates
                        WHERE event.event_id = candidates.event_id
                        RETURNING event.event_id, event.aggregate_id, event.event_type,
                                  event.schema_version, event.event_time,
                                  event.created_at, event.payload::text AS payload
                        """)
                .param("batchSize", batchSize)
                .query(KafkaOutboxRelay::mapRecord)
                .list();
    }

    private void publish(OutboxRecord record) {
        try {
            String envelope = objectMapper.writeValueAsString(envelope(record));
            kafkaTemplate.send(record.eventType(), record.aggregateId(), envelope)
                    .get(sendTimeoutMillis, TimeUnit.MILLISECONDS);
            markPublished(record.eventId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(record.eventId(), exception);
        } catch (Exception exception) {
            markFailed(record.eventId(), exception);
        }
    }

    private Map<String, Object> envelope(OutboxRecord record) throws JsonProcessingException {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("event_id", record.eventId().toString());
        envelope.put("event_type", record.eventType());
        envelope.put("schema_version", record.schemaVersion());
        envelope.put("event_time", record.eventTime().toString());
        envelope.put("ingested_at", record.createdAt().toString());
        envelope.put("producer", "seekflux-outbox-relay");
        envelope.put("payload", objectMapper.readTree(record.payload()));
        return envelope;
    }

    private void markPublished(UUID eventId) {
        jdbcClient.sql("""
                        UPDATE outbox.events
                        SET status = 'PUBLISHED', published_at = now(), locked_at = NULL,
                            last_error = NULL
                        WHERE event_id = :eventId AND status = 'PUBLISHING'
                        """)
                .param("eventId", eventId)
                .update();
    }

    private void markFailed(UUID eventId, Throwable error) {
        String rawMessage = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        String message = rawMessage.length() > 2_000 ? rawMessage.substring(0, 2_000) : rawMessage;
        jdbcClient.sql("""
                        UPDATE outbox.events
                        SET status = 'PENDING', attempts = attempts + 1,
                            next_attempt_at = now() + LEAST(attempts + 1, 60) * interval '1 second',
                            locked_at = NULL, last_error = :lastError
                        WHERE event_id = :eventId AND status = 'PUBLISHING'
                        """)
                .param("eventId", eventId)
                .param("lastError", message)
                .update();
        logger.warn("Failed to publish outbox event {}: {}", eventId, message);
    }

    private static OutboxRecord mapRecord(ResultSet row, int rowNumber) throws SQLException {
        return new OutboxRecord(
                row.getObject("event_id", UUID.class),
                row.getString("aggregate_id"),
                row.getString("event_type"),
                row.getInt("schema_version"),
                instant(row, "event_time"),
                instant(row, "created_at"),
                row.getString("payload"));
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    record OutboxRecord(
            UUID eventId,
            String aggregateId,
            String eventType,
            int schemaVersion,
            Instant eventTime,
            Instant createdAt,
            String payload) {
    }
}
