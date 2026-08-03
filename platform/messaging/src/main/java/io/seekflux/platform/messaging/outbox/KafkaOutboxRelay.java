package io.seekflux.platform.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public final class KafkaOutboxRelay {

    private static final Logger logger = LoggerFactory.getLogger(KafkaOutboxRelay.class);

    private final DatabaseClient databaseClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final int batchSize;

    public KafkaOutboxRelay(
            DatabaseClient databaseClient,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${seekflux.outbox.batch-size:50}") int batchSize) {
        this.databaseClient = databaseClient;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${seekflux.outbox.relay-delay-ms:1000}")
    public void relay() {
        claimBatch()
                .flatMap(this::publish, 8)
                .doOnError(error -> logger.error("Outbox relay iteration failed", error))
                .onErrorResume(error -> Mono.empty())
                .subscribe();
    }

    Flux<OutboxRecord> claimBatch() {
        return databaseClient.sql("""
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
                .bind("batchSize", batchSize)
                .map((row, metadata) -> mapRecord(row))
                .all();
    }

    private Mono<Void> publish(OutboxRecord record) {
        String envelope;
        try {
            envelope = objectMapper.writeValueAsString(envelope(record));
        } catch (JsonProcessingException exception) {
            return markFailed(record.eventId(), exception);
        }
        return Mono.fromFuture(kafkaTemplate.send(record.eventType(), record.aggregateId(), envelope))
                .flatMap(result -> markPublished(record.eventId()))
                .onErrorResume(error -> markFailed(record.eventId(), error));
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

    private Mono<Void> markPublished(UUID eventId) {
        return databaseClient.sql("""
                        UPDATE outbox.events
                        SET status = 'PUBLISHED', published_at = now(), locked_at = NULL,
                            last_error = NULL
                        WHERE event_id = :eventId AND status = 'PUBLISHING'
                        """)
                .bind("eventId", eventId)
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Mono<Void> markFailed(UUID eventId, Throwable error) {
        String rawMessage = error.getMessage() == null
                ? error.getClass().getSimpleName()
                : error.getMessage();
        String message = rawMessage.length() > 2_000 ? rawMessage.substring(0, 2_000) : rawMessage;
        return databaseClient.sql("""
                        UPDATE outbox.events
                        SET status = 'PENDING', attempts = attempts + 1,
                            next_attempt_at = now() + LEAST(attempts + 1, 60) * interval '1 second',
                            locked_at = NULL, last_error = :lastError
                        WHERE event_id = :eventId AND status = 'PUBLISHING'
                        """)
                .bind("eventId", eventId)
                .bind("lastError", message)
                .fetch()
                .rowsUpdated()
                .doOnNext(ignored -> logger.warn("Failed to publish outbox event {}: {}", eventId, message))
                .then();
    }

    private static OutboxRecord mapRecord(Row row) {
        return new OutboxRecord(
                required(row, "event_id", UUID.class),
                required(row, "aggregate_id", String.class),
                required(row, "event_type", String.class),
                required(row, "schema_version", Integer.class),
                required(row, "event_time", Instant.class),
                required(row, "created_at", Instant.class),
                required(row, "payload", String.class));
    }

    private static <T> T required(Row row, String column, Class<T> type) {
        T value = row.get(column, type);
        if (value == null) {
            throw new IllegalStateException("outbox column must not be null: " + column);
        }
        return value;
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
