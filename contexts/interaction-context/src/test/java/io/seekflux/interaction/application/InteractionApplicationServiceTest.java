package io.seekflux.interaction.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.seekflux.interaction.domain.InteractionSignal;
import io.seekflux.interaction.domain.InteractionSurface;
import io.seekflux.interaction.domain.InteractionType;
import io.seekflux.interaction.port.in.InteractionBatchReceipt;
import io.seekflux.interaction.port.in.ReportInteractionsCommand;
import io.seekflux.interaction.port.out.InteractionBatch;
import io.seekflux.interaction.port.out.InteractionRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InteractionApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T01:00:00Z");

    @Test
    void validatesAndPassesAStableBatchHashToTheRepository() {
        AtomicReference<InteractionBatch> captured = new AtomicReference<>();
        InteractionRepository repository = batch -> {
            captured.set(batch);
            return new InteractionBatchReceipt(batch.batchId(), false, 0, 0, 0, List.of());
        };
        UUID batchId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        InteractionApplicationService service = new InteractionApplicationService(
                repository,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> batchId);

        service.report(new ReportInteractionsCommand("batch-key", "user-1", List.of(signal(NOW))));

        assertThat(captured.get().batchId()).isEqualTo(batchId);
        assertThat(captured.get().requestHash()).hasSize(64);
        assertThat(captured.get().receivedAt()).isEqualTo(NOW);
    }

    @Test
    void rejectsAnEmptyBatchBeforePersistence() {
        InteractionApplicationService service = service(batch -> null);

        assertThatThrownBy(() -> service.report(new ReportInteractionsCommand("batch-key", "user-1", List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 100");
    }

    @Test
    void rejectsAnEventMoreThanFiveMinutesInTheFuture() {
        InteractionApplicationService service = service(batch -> null);

        assertThatThrownBy(() -> service.report(new ReportInteractionsCommand(
                "batch-key", "user-1", List.of(signal(NOW.plusSeconds(301))))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 minutes");
    }

    private static InteractionApplicationService service(InteractionRepository repository) {
        return new InteractionApplicationService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InteractionSignal signal(Instant time) {
        return new InteractionSignal(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                InteractionType.EXPOSURE,
                "request-1",
                "trace-1",
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                1,
                InteractionSurface.SEARCH,
                time);
    }
}
