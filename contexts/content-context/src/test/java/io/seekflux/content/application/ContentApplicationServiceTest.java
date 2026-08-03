package io.seekflux.content.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seekflux.content.domain.Content;
import io.seekflux.content.domain.ContentId;
import io.seekflux.content.domain.ContentStatus;
import io.seekflux.content.port.in.CompleteContentProfileCommand;
import io.seekflux.content.port.in.SubmitContentCommand;
import io.seekflux.content.port.out.ContentEvent;
import io.seekflux.content.port.out.ContentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class ContentApplicationServiceTest {

    private final InMemoryContentRepository repository = new InMemoryContentRepository();
    private final AtomicLong sequence = new AtomicLong(1);
    private ContentApplicationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-02T10:00:00Z"), ZoneOffset.UTC);
        service = new ContentApplicationService(
                repository, clock, () -> new UUID(0, sequence.getAndIncrement()));
    }

    @Test
    void storesSubmissionAndOutboxEventTogether() {
        SubmitContentCommand command = new SubmitContentCommand(
                "creator-1",
                "s3://seekflux-media/video-1.mp4",
                "杭州露营",
                "周末路线",
                List.of("露营", "杭州"));

        StepVerifier.create(service.submit(command))
                .assertNext(view -> {
                    assertEquals(ContentStatus.SUBMITTED, view.status());
                    assertEquals(0, view.version());
                })
                .verifyComplete();

        assertEquals(1, repository.contents.size());
        assertEquals(ContentApplicationService.CONTENT_SUBMITTED,
                repository.events.getFirst().eventType());
    }

    @Test
    void completesAndPublishesAProfileIdempotently() {
        ContentId contentId = service.submit(new SubmitContentCommand(
                        "creator-1", "s3://bucket/video.mp4", "标题", "描述", List.of("标签")))
                .block().id();
        CompleteContentProfileCommand profile = new CompleteContentProfileCommand(
                contentId, 1, "标题 — 描述", List.of("标签"), "");

        service.completeProfile(profile).block();
        service.publish(contentId).block();
        service.publish(contentId).block();

        StepVerifier.create(service.get(contentId))
                .assertNext(view -> {
                    assertEquals(ContentStatus.PUBLISHED, view.status());
                    assertEquals(2, view.version());
                    assertEquals(1, view.profile().version());
                })
                .verifyComplete();
        assertEquals(3, repository.events.size());
        assertEquals(ContentApplicationService.CONTENT_PROFILE_PUBLISHED,
                repository.events.getLast().eventType());
    }

    @Test
    void reportsMissingContent() {
        StepVerifier.create(service.get(new ContentId(new UUID(9, 9))))
                .expectError(ContentNotFoundException.class)
                .verify();
    }

    private static final class InMemoryContentRepository implements ContentRepository {

        private final Map<ContentId, Content> contents = new LinkedHashMap<>();
        private final List<ContentEvent> events = new ArrayList<>();

        @Override
        public Mono<Content> findById(ContentId contentId) {
            return Mono.justOrEmpty(contents.get(contentId));
        }

        @Override
        public Mono<Void> insert(Content content, ContentEvent event) {
            contents.put(content.id(), content);
            events.add(event);
            return Mono.empty();
        }

        @Override
        public Mono<Boolean> update(Content content, long expectedVersion, ContentEvent event) {
            Content current = contents.get(content.id());
            if (current == null || current.version() != expectedVersion) {
                return Mono.just(false);
            }
            contents.put(content.id(), content);
            events.add(event);
            return Mono.just(true);
        }
    }
}
