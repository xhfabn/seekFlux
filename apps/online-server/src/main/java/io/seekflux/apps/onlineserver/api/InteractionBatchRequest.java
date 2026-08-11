package io.seekflux.apps.onlineserver.api;

import io.seekflux.interaction.domain.InteractionSignal;
import io.seekflux.interaction.domain.InteractionSurface;
import io.seekflux.interaction.domain.InteractionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InteractionBatchRequest(
        @NotEmpty @Size(max = 100) List<@Valid Event> events) {

    public record Event(
            @NotNull UUID eventId,
            @NotNull InteractionType eventType,
            @NotBlank @Size(max = 128) String requestId,
            @NotBlank @Size(max = 128) String traceId,
            @NotNull UUID contentId,
            @Min(1) @Max(10_000) int position,
            @NotNull InteractionSurface surface,
            @NotNull Instant eventTime) {

        InteractionSignal toSignal() {
            return new InteractionSignal(
                    eventId, eventType, requestId, traceId,
                    contentId, position, surface, eventTime);
        }
    }
}
