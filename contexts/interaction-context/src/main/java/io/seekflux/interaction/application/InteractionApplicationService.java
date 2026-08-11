package io.seekflux.interaction.application;

import io.seekflux.interaction.domain.InteractionSignal;
import io.seekflux.interaction.port.in.InteractionBatchReceipt;
import io.seekflux.interaction.port.in.ReportInteractionsCommand;
import io.seekflux.interaction.port.in.ReportInteractionsUseCase;
import io.seekflux.interaction.port.out.InteractionBatch;
import io.seekflux.interaction.port.out.InteractionRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class InteractionApplicationService implements ReportInteractionsUseCase {

    private static final int MAX_BATCH_SIZE = 100;
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);

    private final InteractionRepository repository;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;

    public InteractionApplicationService(InteractionRepository repository, Clock clock) {
        this(repository, clock, UUID::randomUUID);
    }

    InteractionApplicationService(
            InteractionRepository repository,
            Clock clock,
            Supplier<UUID> idGenerator) {
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.idGenerator = Objects.requireNonNull(idGenerator, "id generator must not be null");
    }

    @Override
    public InteractionBatchReceipt report(ReportInteractionsCommand command) {
        Objects.requireNonNull(command, "interaction command must not be null");
        String idempotencyKey = requireText(command.idempotencyKey(), "idempotency key", 128);
        String userId = requireText(command.userId(), "user id", 128);
        List<InteractionSignal> events = command.events() == null ? List.of() : List.copyOf(command.events());
        if (events.isEmpty() || events.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("interaction batch must contain between 1 and 100 events");
        }
        Instant now = clock.instant();
        Instant latestAllowed = now.plus(MAX_FUTURE_SKEW);
        if (events.stream().anyMatch(event -> event.eventTime().isAfter(latestAllowed))) {
            throw new IllegalArgumentException("interaction event time must not be more than 5 minutes in the future");
        }
        return repository.ingest(new InteractionBatch(
                idGenerator.get(), idempotencyKey, hash(userId, events), userId, events, now));
    }

    private static String hash(String userId, List<InteractionSignal> events) {
        StringBuilder canonical = new StringBuilder(userId);
        for (InteractionSignal event : events) {
            canonical.append('\n').append(event.eventId())
                    .append('|').append(event.eventType())
                    .append('|').append(event.requestId())
                    .append('|').append(event.traceId())
                    .append('|').append(event.contentId())
                    .append('|').append(event.position())
                    .append('|').append(event.surface())
                    .append('|').append(event.eventTime());
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }
}
