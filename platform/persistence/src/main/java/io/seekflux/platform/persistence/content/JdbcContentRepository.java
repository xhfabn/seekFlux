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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcContentRepository implements ContentRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public JdbcContentRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Content> findById(ContentId contentId) {
        return jdbcClient.sql("""
                        SELECT content_id, creator_id, media_uri, title, description,
                               source_tags::text AS source_tags, status, profile_version,
                               profile_summary, profile_tags::text AS profile_tags,
                               profile_transcript, aggregate_version, created_at, updated_at,
                               published_at, withdrawn_at
                        FROM content.contents
                        WHERE content_id = :contentId
                        """)
                .param("contentId", contentId.value())
                .query(this::mapContent)
                .optional();
    }

    @Override
    @Transactional
    public void insert(Content content, ContentEvent event) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO content.contents (
                            content_id, creator_id, media_uri, title, description, source_tags,
                            status, aggregate_version, created_at, updated_at
                        ) VALUES (
                            :contentId, :creatorId, :mediaUri, :title, :description,
                            CAST(:sourceTags AS jsonb), :status, :version, :createdAt, :updatedAt
                        )
                        """)
                .param("contentId", content.id().value())
                .param("creatorId", content.creatorId())
                .param("mediaUri", content.mediaUri())
                .param("title", content.title())
                .param("description", content.description())
                .param("sourceTags", toJson(content.sourceTags()))
                .param("status", content.status().name())
                .param("version", content.version())
                .param("createdAt", databaseTime(content.createdAt()))
                .param("updatedAt", databaseTime(content.updatedAt()))
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("content insert did not affect exactly one row");
        }
        insertEvent(event);
    }

    @Override
    @Transactional
    public boolean update(Content content, long expectedVersion, ContentEvent event) {
        ContentProfile profile = content.profile();
        int updated = jdbcClient.sql("""
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
                .param("status", content.status().name())
                .param("profileVersion", profile == null ? null : profile.version(), Types.INTEGER)
                .param("profileSummary", profile == null ? null : profile.summary(), Types.VARCHAR)
                .param("profileTags", toJson(profile == null ? List.of() : profile.tags()))
                .param("profileTranscript", profile == null ? null : profile.transcript(), Types.VARCHAR)
                .param("newVersion", content.version())
                .param("updatedAt", databaseTime(content.updatedAt()))
                .param("publishedAt", databaseTime(content.publishedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("withdrawnAt", databaseTime(content.withdrawnAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("contentId", content.id().value())
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated == 0) {
            return false;
        }
        insertEvent(event);
        return true;
    }

    private void insertEvent(ContentEvent event) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO outbox.events (
                            event_id, aggregate_type, aggregate_id, event_type,
                            schema_version, event_time, payload
                        ) VALUES (
                            :eventId, 'Content', :aggregateId, :eventType,
                            :schemaVersion, :eventTime, CAST(:payload AS jsonb)
                        )
                        """)
                .param("eventId", event.eventId())
                .param("aggregateId", event.contentId().toString())
                .param("eventType", event.eventType())
                .param("schemaVersion", event.schemaVersion())
                .param("eventTime", databaseTime(event.eventTime()))
                .param("payload", toJson(event.payload()))
                .update();
        if (inserted != 1) {
            throw new IllegalStateException("outbox insert did not affect exactly one row");
        }
    }

    private Content mapContent(ResultSet row, int rowNumber) throws SQLException {
        Integer profileVersion = row.getObject("profile_version", Integer.class);
        ContentProfile profile = null;
        if (profileVersion != null) {
            profile = new ContentProfile(
                    profileVersion,
                    row.getString("profile_summary"),
                    fromJson(row.getString("profile_tags")),
                    row.getString("profile_transcript"));
        }
        return Content.restore(
                new ContentId(row.getObject("content_id", UUID.class)),
                row.getString("creator_id"),
                row.getString("media_uri"),
                row.getString("title"),
                row.getString("description"),
                fromJson(row.getString("source_tags")),
                ContentStatus.valueOf(row.getString("status")),
                profile,
                row.getLong("aggregate_version"),
                instant(row, "created_at"),
                instant(row, "updated_at"),
                nullableInstant(row, "published_at"),
                nullableInstant(row, "withdrawn_at"));
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

    private static OffsetDateTime databaseTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
