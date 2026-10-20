package io.seekflux.core.feature.api;

import io.seekflux.core.feature.domain.InteractionSignal;
import java.util.List;

public record ReportInteractionsCommand(
        String idempotencyKey,
        String userId,
        List<InteractionSignal> events) {
}
