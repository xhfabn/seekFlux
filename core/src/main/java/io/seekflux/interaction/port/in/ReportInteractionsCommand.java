package io.seekflux.interaction.port.in;

import io.seekflux.interaction.domain.InteractionSignal;
import java.util.List;

public record ReportInteractionsCommand(
        String idempotencyKey,
        String userId,
        List<InteractionSignal> events) {
}
