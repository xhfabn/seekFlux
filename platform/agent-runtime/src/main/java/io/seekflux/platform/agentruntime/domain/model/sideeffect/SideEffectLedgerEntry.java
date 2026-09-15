package io.seekflux.platform.agentruntime.domain.model.sideeffect;

import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import java.time.Instant;
import java.util.Map;

public record SideEffectLedgerEntry(
        int schemaVersion,
        String ledgerId,
        String sessionId,
        String requestId,
        String turnId,
        String attemptId,
        int step,
        int callIndex,
        String toolCallId,
        String toolName,
        String toolSchemaVersion,
        String idempotencyKey,
        SideEffectStatus status,
        String requestDigest,
        String resultDigest,
        AgentToolResult result,
        String reconciliationMethod,
        String reconciliationNote,
        Instant createdAt,
        Instant updatedAt) {

    public SideEffectLedgerEntry {
        if (schemaVersion < 1 || step < 1 || callIndex < 0) {
            throw new IllegalArgumentException("invalid side-effect ledger version or position");
        }
        requireText(ledgerId, "ledger id");
        requireText(sessionId, "session id");
        requireText(requestId, "request id");
        requireText(turnId, "turn id");
        requireText(attemptId, "attempt id");
        requireText(toolCallId, "tool call id");
        requireText(toolName, "tool name");
        requireText(toolSchemaVersion, "tool schema version");
        requireText(idempotencyKey, "idempotency key");
        requireText(requestDigest, "request digest");
        if (status == null || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("ledger status and timestamps are required");
        }
        if (status.terminal() && result == null) {
            throw new IllegalArgumentException("terminal ledger state requires a Tool result");
        }
        if (!status.terminal() && result != null) {
            throw new IllegalArgumentException("non-terminal ledger state cannot contain a Tool result");
        }
        if (result != null && (resultDigest == null || resultDigest.isBlank())) {
            throw new IllegalArgumentException("a ledger result requires its digest");
        }
    }

    public Map<String, Object> externalReceipt() {
        return result == null ? Map.of() : result.externalReceipt();
    }

    public SideEffectLedgerEntry forAttempt(
            String nextAttemptId,
            SideEffectStatus nextStatus,
            String nextResultDigest,
            AgentToolResult nextResult,
            String method,
            String note,
            Instant time) {
        return new SideEffectLedgerEntry(
                schemaVersion, ledgerId, sessionId, requestId, turnId, nextAttemptId,
                step, callIndex, toolCallId, toolName, toolSchemaVersion, idempotencyKey,
                nextStatus, requestDigest, nextResultDigest, nextResult, method, note,
                createdAt, time);
    }

    public SideEffectLedgerEntry withStoredState(
            SideEffectStatus storedStatus,
            Instant storedUpdatedAt) {
        return new SideEffectLedgerEntry(
                schemaVersion, ledgerId, sessionId, requestId, turnId, attemptId,
                step, callIndex, toolCallId, toolName, toolSchemaVersion, idempotencyKey,
                storedStatus, requestDigest, resultDigest, result,
                reconciliationMethod, reconciliationNote, createdAt, storedUpdatedAt);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
