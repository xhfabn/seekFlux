package io.seekflux.content.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContentTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-08-02T10:00:00Z");

    @Test
    void followsProfilePublicationLifecycle() {
        Content submitted = content();
        ContentProfile profile = new ContentProfile(1, "露营路线", List.of("露营", "杭州"), "");

        Content ready = submitted.completeProfile(profile, SUBMITTED_AT.plusSeconds(10));
        Content published = ready.publish(SUBMITTED_AT.plusSeconds(20));

        assertEquals(ContentStatus.SUBMITTED, submitted.status());
        assertEquals(ContentStatus.PROFILE_READY, ready.status());
        assertEquals(1, ready.version());
        assertEquals(ContentStatus.PUBLISHED, published.status());
        assertEquals(2, published.version());
        assertEquals(SUBMITTED_AT.plusSeconds(20), published.publishedAt());
        assertNotSame(submitted, ready);
    }

    @Test
    void cannotPublishBeforeProfileIsReady() {
        Content submitted = content();

        assertThrows(ContentStateException.class,
                () -> submitted.publish(SUBMITTED_AT.plusSeconds(1)));
    }

    @Test
    void repeatingTheSameProfileAndPublicationIsIdempotent() {
        ContentProfile profile = new ContentProfile(1, "露营路线", List.of("露营"), "");
        Content ready = content().completeProfile(profile, SUBMITTED_AT.plusSeconds(1));
        Content published = ready.publish(SUBMITTED_AT.plusSeconds(2));

        assertSame(ready, ready.completeProfile(profile, SUBMITTED_AT.plusSeconds(3)));
        assertSame(published, published.publish(SUBMITTED_AT.plusSeconds(3)));
    }

    @Test
    void rejectsConflictingPayloadForAnExistingProfileVersion() {
        Content ready = content().completeProfile(
                new ContentProfile(1, "原画像", List.of(), ""), SUBMITTED_AT.plusSeconds(1));

        assertThrows(ContentStateException.class, () -> ready.completeProfile(
                new ContentProfile(1, "不同画像", List.of(), ""), SUBMITTED_AT.plusSeconds(2)));
    }

    @Test
    void publishedContentCanBeConfiguredWithANewerProfileAndRepublished() {
        Content published = content()
                .completeProfile(new ContentProfile(1, "原画像", List.of("露营"), ""),
                        SUBMITTED_AT.plusSeconds(1))
                .publish(SUBMITTED_AT.plusSeconds(2));

        Content reconfigured = published.completeProfile(
                new ContentProfile(2, "人工配置的新画像", List.of("亲子", "露营"), ""),
                SUBMITTED_AT.plusSeconds(3));
        Content republished = reconfigured.publish(SUBMITTED_AT.plusSeconds(4));

        assertEquals(ContentStatus.PROFILE_READY, reconfigured.status());
        assertEquals(2, reconfigured.profile().version());
        assertEquals(ContentStatus.PUBLISHED, republished.status());
        assertEquals(4, republished.version());
    }

    @Test
    void withdrawnContentCannotBeRepublished() {
        Content withdrawn = content().withdraw(SUBMITTED_AT.plusSeconds(1));

        assertEquals(ContentStatus.WITHDRAWN, withdrawn.status());
        assertThrows(ContentStateException.class,
                () -> withdrawn.publish(SUBMITTED_AT.plusSeconds(2)));
    }

    @Test
    void preservesArticleAssetsBodyAndProvenance() {
        Content article = Content.submit(
                new ContentId(UUID.fromString("0198b334-a7c0-7000-8000-000000000002")),
                "creator-2", ContentType.ARTICLE, "https://media.example/cover.jpg",
                List.of("https://media.example/cover.jpg", "https://media.example/detail.jpg"),
                "杭州咖啡地图", "五家小店", "第一站从湖滨开始。", List.of("咖啡"),
                new ContentSource("qilin", "note-2", "https://example.com/note-2",
                        "dataset-author", "verify-original-rights"), SUBMITTED_AT);

        assertEquals(ContentType.ARTICLE, article.contentType());
        assertEquals(2, article.assetUris().size());
        assertEquals("第一站从湖滨开始。", article.body());
        assertEquals("note-2", article.source().externalId());
    }

    private Content content() {
        return Content.submit(
                new ContentId(UUID.fromString("0198b334-a7c0-7000-8000-000000000001")),
                "creator-1",
                "s3://seekflux-media/video-1.mp4",
                "杭州露营",
                "周末路线",
                List.of("露营"),
                SUBMITTED_AT);
    }
}
