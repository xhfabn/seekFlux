package io.seekflux.apps.contentserver.comment;

import io.seekflux.core.content.ContentNotFoundException;
import io.seekflux.core.content.domain.ContentId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommentService {
    private final NamedParameterJdbcTemplate jdbc;

    public CommentService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Page list(UUID contentId, UUID before, int limit) {
        requirePublished(contentId);
        var params = new MapSqlParameterSource("contentId", contentId).addValue("before", before)
                .addValue("limit", limit + 1);
        if (before != null) {
            Integer found = jdbc.queryForObject("SELECT count(*) FROM content.comments WHERE content_id = :contentId AND comment_id = :before", params, Integer.class);
            if (found == null || found == 0) throw new IllegalArgumentException("unknown comment cursor");
        }
        List<Comment> rows = jdbc.query("""
                SELECT comment_id, content_id, user_id, body, created_at FROM content.comments
                WHERE content_id = :contentId AND (CAST(:before AS uuid) IS NULL OR (created_at, comment_id) <
                    (SELECT created_at, comment_id FROM content.comments WHERE content_id = :contentId AND comment_id = :before))
                ORDER BY created_at DESC, comment_id DESC LIMIT :limit
                """, params, (rs, n) -> new Comment(rs.getObject("comment_id", UUID.class),
                rs.getObject("content_id", UUID.class), rs.getString("user_id"), rs.getString("body"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()));
        boolean more = rows.size() > limit;
        List<Comment> items = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        return new Page(items, more ? items.getLast().commentId() : null);
    }

    @Transactional
    public Comment create(UUID contentId, UUID commentId, String userId, String body) {
        String user = normalize(userId, 128, "user id");
        String text = normalize(body, 1000, "comment");
        requirePublished(contentId);
        var params = new MapSqlParameterSource("commentId", commentId).addValue("contentId", contentId)
                .addValue("userId", user).addValue("body", text);
        jdbc.update("""
                INSERT INTO content.comments(comment_id, content_id, user_id, body)
                VALUES (:commentId, :contentId, :userId, :body) ON CONFLICT (comment_id) DO NOTHING
                """, params);
        Comment saved = jdbc.queryForObject("SELECT comment_id, content_id, user_id, body, created_at FROM content.comments WHERE comment_id = :commentId",
                params, (rs, n) -> new Comment(rs.getObject("comment_id", UUID.class), rs.getObject("content_id", UUID.class),
                        rs.getString("user_id"), rs.getString("body"), rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant()));
        if (saved == null || !saved.contentId().equals(contentId) || !saved.userId().equals(user) || !saved.body().equals(text)) {
            throw new IllegalArgumentException("comment id already used for a different comment");
        }
        return saved;
    }

    private void requirePublished(UUID contentId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM content.contents WHERE content_id = :id AND status = 'PUBLISHED'",
                new MapSqlParameterSource("id", contentId), Integer.class);
        if (count == null || count == 0) throw new ContentNotFoundException(new ContentId(contentId));
    }

    private static String normalize(String value, int max, String name) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > max)
            throw new IllegalArgumentException(name + " must contain 1 to " + max + " characters");
        return value.strip();
    }

    public record Comment(UUID commentId, UUID contentId, String userId, String body, Instant createdAt) { }
    public record Page(List<Comment> items, UUID nextBefore) { }
}
