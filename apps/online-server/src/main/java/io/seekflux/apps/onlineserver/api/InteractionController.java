package io.seekflux.apps.onlineserver.api;

import io.seekflux.interaction.port.in.InteractionBatchReceipt;
import io.seekflux.interaction.port.in.ReportInteractionsCommand;
import io.seekflux.interaction.port.in.ReportInteractionsUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class InteractionController {

    private final ReportInteractionsUseCase reportInteractions;

    public InteractionController(ReportInteractionsUseCase reportInteractions) {
        this.reportInteractions = reportInteractions;
    }

    @PostMapping("/v1/interactions:batch")
    public ResponseEntity<InteractionBatchReceipt> report(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestHeader("X-User-Id") @NotBlank @Size(max = 128) String userId,
            @Valid @RequestBody InteractionBatchRequest request) {
        InteractionBatchReceipt receipt = reportInteractions.report(new ReportInteractionsCommand(
                idempotencyKey,
                userId,
                request.events().stream().map(InteractionBatchRequest.Event::toSignal).toList()));
        return ResponseEntity.status(receipt.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(receipt);
    }
}
