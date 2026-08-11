package io.seekflux.apps.onlineserver.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.seekflux.interaction.application.InteractionIdempotencyConflictException;
import io.seekflux.interaction.domain.InteractionDisposition;
import io.seekflux.interaction.port.in.InteractionBatchReceipt;
import io.seekflux.interaction.port.in.InteractionEventReceipt;
import io.seekflux.interaction.port.in.ReportInteractionsUseCase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InteractionControllerTest {

    private static final String EVENT_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void acceptsACompleteAttributionContract() throws Exception {
        ReportInteractionsUseCase useCase = command -> new InteractionBatchReceipt(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                false,
                1,
                0,
                0,
                List.of(new InteractionEventReceipt(
                        UUID.fromString(EVENT_ID), InteractionDisposition.ACCEPTED, null)));
        MockMvc client = client(useCase);

        client.perform(post("/v1/interactions:batch")
                        .header("Idempotency-Key", "batch-1")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.events[0].eventId").value(EVENT_ID));
    }

    @Test
    void returnsConflictForReusedKeyWithDifferentBody() throws Exception {
        MockMvc client = client(command -> {
            throw new InteractionIdempotencyConflictException(command.idempotencyKey());
        });

        client.perform(post("/v1/interactions:batch")
                        .header("Idempotency-Key", "batch-1")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTERACTION_IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void rejectsMissingUserAttribution() throws Exception {
        MockMvc client = client(command -> null);

        client.perform(post("/v1/interactions:batch")
                        .header("Idempotency-Key", "batch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isBadRequest());
    }

    private static MockMvc client(ReportInteractionsUseCase useCase) {
        return MockMvcBuilders.standaloneSetup(new InteractionController(useCase))
                .setControllerAdvice(new SearchExceptionHandler())
                .build();
    }

    private static String body() {
        return """
                {
                  "events": [{
                    "eventId": "%s",
                    "eventType": "EXPOSURE",
                    "requestId": "request-1",
                    "traceId": "trace-1",
                    "contentId": "00000000-0000-0000-0000-000000000002",
                    "position": 1,
                    "surface": "SEARCH",
                    "eventTime": "2026-08-11T01:00:00Z"
                  }]
                }
                """.formatted(EVENT_ID);
    }
}
