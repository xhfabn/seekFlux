package io.seekflux.platform.persistence.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectStatus;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SideEffectLedgerJsonTest {

    @Test
    void roundTripsExternalReceiptAndReconciliationFacts() throws Exception {
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        SideEffectLedgerEntry entry = new SideEffectLedgerEntry(
                1,
                "92f27e18-d74c-4a9e-af39-f49bdcabfe5a",
                "session",
                "request",
                "turn",
                "587608e5-4383-4c5f-895b-79a35d541b2a",
                1,
                0,
                "call-1",
                "publish",
                "publish-v1",
                "tool-call:call-1",
                SideEffectStatus.RECONCILED,
                "request-digest",
                "result-digest",
                AgentToolResult.success(
                        Map.of("published", true),
                        "trace-1",
                        Map.of("receiptId", "external-1")),
                "EXTERNAL_STATUS_QUERY",
                "confirmed by receipt",
                now,
                now);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

        SideEffectLedgerEntry decoded = mapper.readValue(
                mapper.writeValueAsString(entry), SideEffectLedgerEntry.class);

        assertEquals(entry, decoded);
        assertEquals("external-1", decoded.externalReceipt().get("receiptId"));
    }
}
