package io.seekflux.core.feature.domain;

import io.seekflux.core.feature.api.InteractionBatchReceipt;

public interface InteractionRepository {

    InteractionBatchReceipt ingest(InteractionBatch batch);
}
