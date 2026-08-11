package io.seekflux.interaction.port.in;

import io.seekflux.interaction.domain.InteractionDisposition;
import java.util.UUID;

public record InteractionEventReceipt(
        UUID eventId,
        InteractionDisposition disposition,
        String reason) {
}
