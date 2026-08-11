package io.seekflux.interaction.port.out;

import io.seekflux.interaction.domain.InteractionSignal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InteractionBatch(
        UUID batchId,
        String idempotencyKey,
        String requestHash,
        String userId,
        List<InteractionSignal> events,
        Instant receivedAt) {

    public InteractionBatch {
        events = List.copyOf(events);
    }
}
