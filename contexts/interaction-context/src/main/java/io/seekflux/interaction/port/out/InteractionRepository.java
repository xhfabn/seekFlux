package io.seekflux.interaction.port.out;

import io.seekflux.interaction.port.in.InteractionBatchReceipt;

public interface InteractionRepository {

    InteractionBatchReceipt ingest(InteractionBatch batch);
}
