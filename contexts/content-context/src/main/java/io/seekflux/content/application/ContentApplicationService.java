package io.seekflux.content.application;

import io.seekflux.content.domain.Content;
import io.seekflux.content.domain.ContentId;
import io.seekflux.content.domain.ContentProfile;
import io.seekflux.content.port.in.CompleteContentProfileCommand;
import io.seekflux.content.port.in.ContentUseCase;
import io.seekflux.content.port.in.ContentView;
import io.seekflux.content.port.in.SubmitContentCommand;
import io.seekflux.content.port.out.ContentEvent;
import io.seekflux.content.port.out.ContentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

public final class ContentApplicationService implements ContentUseCase {

    public static final String CONTENT_SUBMITTED = "content.submitted.v1";
    public static final String CONTENT_PROFILE_READY = "content.profile.ready.v1";
    public static final String CONTENT_PROFILE_PUBLISHED = "content.profile.published.v1";
    public static final String CONTENT_DISTRIBUTION_CHANGED = "content.distribution.changed.v1";

    private final ContentRepository repository;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public ContentApplicationService(ContentRepository repository, Clock clock) {
        this(repository, clock, UUID::randomUUID);
    }

    public ContentApplicationService(
            ContentRepository repository,
            Clock clock,
            Supplier<UUID> idGenerator) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "id generator must not be null");
    }

    @Override
    public Mono<ContentView> submit(SubmitContentCommand command) {
        Objects.requireNonNull(command, "submit command must not be null");
        Instant now = clock.instant();
        Content content = Content.submit(
                new ContentId(idGenerator.get()),
                command.creatorId(),
                command.mediaUri(),
                command.title(),
                command.description(),
                command.sourceTags(),
                now);
        ContentEvent event = event(CONTENT_SUBMITTED, content, now, submittedPayload(content));
        return repository.insert(content, event).thenReturn(ContentView.from(content));
    }

    @Override
    public Mono<ContentView> get(ContentId contentId) {
        Objects.requireNonNull(contentId, "content id must not be null");
        return find(contentId).map(ContentView::from);
    }

    @Override
    public Mono<ContentView> completeProfile(CompleteContentProfileCommand command) {
        Objects.requireNonNull(command, "profile command must not be null");
        ContentProfile profile = new ContentProfile(
                command.profileVersion(), command.summary(), command.tags(), command.transcript());
        return change(command.contentId(), current -> current.completeProfile(profile, clock.instant()),
                CONTENT_PROFILE_READY, this::profilePayload);
    }

    @Override
    public Mono<ContentView> publish(ContentId contentId) {
        return change(contentId, current -> current.publish(clock.instant()),
                CONTENT_PROFILE_PUBLISHED, this::profilePayload);
    }

    @Override
    public Mono<ContentView> withdraw(ContentId contentId) {
        return change(contentId, current -> current.withdraw(clock.instant()),
                CONTENT_DISTRIBUTION_CHANGED,
                content -> Map.of(
                        "content_id", content.id().toString(),
                        "distribution_status", content.status().name()));
    }

    private Mono<ContentView> change(
            ContentId contentId,
            java.util.function.Function<Content, Content> transition,
            String eventType,
            java.util.function.Function<Content, Map<String, Object>> payloadFactory) {
        Objects.requireNonNull(contentId, "content id must not be null");
        return find(contentId).flatMap(current -> {
            Content changed = transition.apply(current);
            if (changed == current) {
                return Mono.just(ContentView.from(current));
            }
            ContentEvent event = event(eventType, changed, changed.updatedAt(), payloadFactory.apply(changed));
            return repository.update(changed, current.version(), event)
                    .flatMap(updated -> updated
                            ? Mono.just(ContentView.from(changed))
                            : Mono.error(new ContentConcurrencyException(contentId)));
        });
    }

    private Mono<Content> find(ContentId contentId) {
        return repository.findById(contentId)
                .switchIfEmpty(Mono.error(new ContentNotFoundException(contentId)));
    }

    private ContentEvent event(
            String eventType,
            Content content,
            Instant eventTime,
            Map<String, Object> payload) {
        return new ContentEvent(idGenerator.get(), eventType, 1, content.id(), eventTime, payload);
    }

    private Map<String, Object> submittedPayload(Content content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content_id", content.id().toString());
        payload.put("creator_id", content.creatorId());
        payload.put("media_uri", content.mediaUri());
        payload.put("title", content.title());
        payload.put("description", content.description());
        payload.put("source_tags", content.sourceTags());
        return payload;
    }

    private Map<String, Object> profilePayload(Content content) {
        ContentProfile profile = Objects.requireNonNull(content.profile(), "profile must be present");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content_id", content.id().toString());
        payload.put("creator_id", content.creatorId());
        payload.put("media_uri", content.mediaUri());
        payload.put("title", content.title());
        payload.put("description", content.description());
        payload.put("profile_version", profile.version());
        payload.put("summary", profile.summary());
        payload.put("tags", profile.tags());
        payload.put("transcript", profile.transcript());
        payload.put("status", content.status().name());
        return payload;
    }
}
