package io.seekflux.platform.agentruntime.application.spi.capability.session;

import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeIngress;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AgentRecoveryStore {

    AgentRecoveryStore NOOP = new AgentRecoveryStore() { };

    default boolean enabled() {
        return false;
    }

    default boolean sideEffectLedgerEnabled() {
        return false;
    }

    default RecoveryPlan commitResume(
            ResumeIngress ingress, long fencingToken, Instant eventTime) {
        return RecoveryPlan.START_NEW;
    }

    default void saveCheckpoint(
            RuntimeCheckpoint checkpoint, long fencingToken, Instant eventTime) {
    }

    default void recordToolDecision(
            RuntimeCheckpoint checkpoint,
            List<ToolCallJournalEntry> calls,
            long fencingToken,
            Instant eventTime) {
    }

    default void markToolExecuting(
            String sessionId,
            String requestId,
            String attemptId,
            List<String> toolCallIds,
            long fencingToken,
            Instant eventTime) {
    }

    default void recordToolResult(
            ToolCallJournalEntry call,
            long fencingToken,
            Instant eventTime) {
    }

    default SideEffectLedgerEntry prepareSideEffect(
            SideEffectLedgerEntry entry,
            long fencingToken,
            Instant eventTime) {
        throw new IllegalStateException("mutating Tool side-effect ledger is not configured");
    }

    default SideEffectLedgerEntry markSideEffectExecuting(
            SideEffectLedgerEntry entry,
            long fencingToken,
            Instant eventTime) {
        throw new IllegalStateException("mutating Tool side-effect ledger is not configured");
    }

    default SideEffectLedgerEntry recordSideEffectResult(
            SideEffectLedgerEntry entry,
            long fencingToken,
            Instant eventTime) {
        throw new IllegalStateException("mutating Tool side-effect ledger is not configured");
    }

    default void markSideEffectsUnknown(
            String sessionId,
            String requestId,
            List<String> toolCallIds,
            long fencingToken,
            Instant eventTime) {
        throw new IllegalStateException("mutating Tool side-effect ledger is not configured");
    }

    default Optional<SideEffectLedgerEntry> findSideEffect(String toolCallId) {
        return Optional.empty();
    }
}
