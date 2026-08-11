package io.seekflux.interaction.port.in;

public interface ReportInteractionsUseCase {

    InteractionBatchReceipt report(ReportInteractionsCommand command);
}
