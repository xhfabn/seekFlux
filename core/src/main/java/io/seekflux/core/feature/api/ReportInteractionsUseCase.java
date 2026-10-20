package io.seekflux.core.feature.api;

public interface ReportInteractionsUseCase {

    InteractionBatchReceipt report(ReportInteractionsCommand command);
}
