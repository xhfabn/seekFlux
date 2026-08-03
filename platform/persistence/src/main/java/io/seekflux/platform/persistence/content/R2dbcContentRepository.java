package io.seekflux.platform.persistence.content;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.content.domain.Content;
import io.seekflux.content.domain.ContentId;
import io.seekflux.content.domain.ContentProfile;
import io.seekflux.content.domain.ContentStatus;
import io.seekflux.content.port.out.ContentEvent;
import io.seekflux.content.port.out.ContentRepository;
import io.r2dbc.spi.Row;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

@Repository
public class R2dbcContentRepository implements ContentRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final DatabaseClient databaseClient;
    private final TransactionalOperator transaction;
    private final ObjectMapper objectMapper;

    public R2dbcContentRepository(
            DatabaseClient databaseClient,
            TransactionalOperator transaction,
            ObjectMapper objectMapper) {
        this.databaseClient = databaseClient;
        this.transaction = transaction;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Content> findById(ContentId contentId) {
        return databaseClient.sql("""
                        SELECT content_id, creator_id, media_uri, title, description,
                               source_tags::text AS source_tags, status, profile_version,
                               profile_summary, profile_tags::text AS profile_tags,
                               profile_transcript, aggregate_version, created_at, updated_at,
                               published_at, withdrawn_at
                        FROM content.contents
                        WHERE content_id = :contentId
                        """)
                .bind("contentId", contentId.value())
                .map((row, metadata) -> mapContent(row))
                .one();
    }

    @Override
    public Mono<Void> insert(Content content, ContentEvent event) {
        Mono<Void> insertContent = databaseClient.sql("""
                        INSERT INTO content.contents (
                            content_id, creator_id, media_uri, title, description, source_tags,
                            status, aggregate_version, created_at, updated_at
                        ) VALUES (
                            :contentId, :creatorId, :mediaUri, :title, :description,
                            CAST(:sourceTags AS jsonb), :status, :version, :createdAt, :updatedAt
                        )
                        """)
                .bind("contentId", content.id().value())
                .bind("creatorId", content.creatorId())
                .bind("mediaUri", content.mediaUri())
                .bind("title", content.title())
                .bind("description", content.description())
                .bind("sourceTags", toJson(content.sourceTags()))
                .bind("status", content.status().name())
                .bind("version", content.version())
                .bind("createdAt", content.createdAt())
                .bind("updatedAt", content.updatedAt())
                .fetch()
                .rowsUpdated()
                .then();
        return transaction.transactional(insertContent.then(insertEvent(event)));
    }

    @Override
    public Mono<Boolean> update(Content content, long expectedVersion, ContentEvent event) {
        DatabaseClient.GenericExecuteSpec statement = databaseClient.sql("""
                        UPDATE content.contents
                        SET status = :status,
                            profile_version = :profileVersion,
                            profile_summary = :profileSummary,
                            profile_tags = CAST(:profileTags AS jsonb),
                            profile_transcript = :profileTranscript,
                            aggregate_version = :newVersion,
                            updated_at = :updatedAt,
                            published_at = :publishedAt,
                            withdrawn_at = :withdrawnAt
                        WHERE content_id = :contentId
                          AND aggregate_version = :expectedVersion
                        """)
                .bind("status", content.status().name())
                .bind("profileTags", toJson(content.profile() == null ? List.of() : content.profile().tags()))
                .bind("newVersion", content.version())
                .bind("updatedAt", content.updatedAt())
                .bind("contentId", content.id().value())
                .bind("expectedVersion", expectedVersion);

        statement = bindNullable(statement, "profileVersion",
                content.profile() == null ? null : content.profile().version(), Integer.class);
        statement = bindNullable(statement, "profileSummary",
                content.profile() == null ? null : content.profile().summary(), String.class);
        statement = bindNullable(statement, "profileTranscript",
                content.profile() == null ? null : content.profile().transcript(), String.class);
        statement = bindNullable(statement, "publishedAt", content.publishedAt(), Instant.class);
        statement = bindNullable(statement, "withdrawnAt", content.withdrawnAt(), Instant.class);

        Mono<Boolean> updateAndAppend = statement.fetch().rowsUpdated().flatMap(updated -> {
            if (updated == 0) {
                return Mono.just(false);
            }
            return insertEvent(event).thenReturn(true);
        });
        return transaction.transactional(updateAndAppend);
    }

    private Mono<Void> insertEvent(ContentEvent event) {
        return databaseClient.sql("""
                        INSERT INTO outbox.events (
                            event_id, aggregate_type, aggregate_id, event_type,
                            schema_version, event_time, payload
                        ) VALUES (
                            :eventId, 'Content', :aggregateId, :eventType,
                            :schemaVersion, :eventTime, CAST(:payload AS jsonb)
                        )
                        """)
                .bind("eventId", event.eventId())
                .bind("aggregateId", event.contentId().toString())
                .bind("eventType", event.eventType())
                .bind("schemaVersion", event.schemaVersion())
                .bind("eventTime", event.eventTime())
                .bind("payload", toJson(event.payload()))
                .fetch()
                .rowsUpdated()
                .then();
    }

    private Content mapContent(Row row) {
        Integer profileVersion = row.get("profile_version", Integer.class);
        ContentProfile profile = null;
        if (profileVersion != null) {
            profile = new ContentProfile(
                    profileVersion,
                    row.get("profile_summary", String.class),
                    fromJson(row.get("profile_tags", String.class)),
                    row.get("profile_transcript", String.class));
        }
        return Content.restore(
                new ContentId(required(row, "content_id", UUID.class)),
                required(row, "creator_id", String.class),
                required(row, "media_uri", String.class),
                required(row, "title", String.class),
                required(row, "description", String.class),
                fromJson(required(row, "source_tags", String.class)),
                ContentStatus.valueOf(required(row, "status", String.class)),
                profile,
                required(row, "aggregate_version", Long.class),
                required(row, "created_at", Instant.class),
                required(row, "updated_at", Instant.class),
                row.get("published_at", Instant.class),
                row.get("withdrawn_at", Instant.class));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize content persistence value", exception);
        }
    }

    private List<String> fromJson(String value) {
        if (value == null) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to deserialize content persistence value", exception);
        }
    }

    private static <T> T required(Row row, String column, Class<T> type) {
        T value = row.get(column, type);
        if (value == null) {
            throw new IllegalStateException("database column must not be null: " + column);
        }
        return value;
    }

    private static <T> DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec statement,
            String name,
            T value,
            Class<T> type) {
        return value == null ? statement.bindNull(name, type) : statement.bind(name, value);
    }
}
