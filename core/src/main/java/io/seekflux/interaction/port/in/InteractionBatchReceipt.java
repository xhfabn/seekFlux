package io.seekflux.interaction.port.in;

import java.util.List;
import java.util.UUID;

public record InteractionBatchReceipt(
        UUID batchId,
        boolean replayed,
        int acceptedCount,
        int duplicateCount,
        int rejectedCount,
        List<InteractionEventReceipt> events) {

    public InteractionBatchReceipt {
        events = List.copyOf(events);
    }

    public InteractionBatchReceipt asReplay() {
        return replayed ? this : new InteractionBatchReceipt(
                batchId, true, acceptedCount, duplicateCount, rejectedCount, events);
    }
}
