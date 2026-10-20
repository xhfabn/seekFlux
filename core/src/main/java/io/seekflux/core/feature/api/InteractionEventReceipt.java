package io.seekflux.core.feature.api;

import io.seekflux.core.feature.domain.InteractionDisposition;
import java.util.UUID;

public record InteractionEventReceipt(
        UUID eventId,
        InteractionDisposition disposition,
        String reason) {
}
