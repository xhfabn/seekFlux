package io.seekflux.platform.agentruntime.application.spi.capability.session;

import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeIngress;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import java.time.Instant;
import java.util.List;

public interface AgentRecoveryStore {

    AgentRecoveryStore NOOP = new AgentRecoveryStore() { };

    default boolean enabled() {
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
}
