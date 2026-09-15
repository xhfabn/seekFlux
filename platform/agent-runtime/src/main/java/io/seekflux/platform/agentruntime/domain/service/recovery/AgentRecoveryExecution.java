package io.seekflux.platform.agentruntime.domain.service.recovery;

import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import java.time.Clock;
import java.util.List;
import java.util.Map;

public final class AgentRecoveryExecution {

    public static final AgentRecoveryExecution DISABLED = new AgentRecoveryExecution(
            AgentRecoveryStore.NOOP,
            RecoveryPlan.START_NEW,
            0,
            0,
            Map.of(),
            Clock.systemUTC(),
            RecoveryFaultInjector.NONE);

    private final AgentRecoveryStore store;
    private final RecoveryPlan plan;
    private final long fencingToken;
    private final long messageCutoff;
    private final Map<String, Object> persistentFeatures;
    private final Clock clock;
    private final RecoveryFaultInjector faultInjector;

    public AgentRecoveryExecution(
            AgentRecoveryStore store,
            RecoveryPlan plan,
            long fencingToken,
            long messageCutoff,
            Map<String, Object> persistentFeatures,
            Clock clock,
            RecoveryFaultInjector faultInjector) {
        this.store = store == null ? AgentRecoveryStore.NOOP : store;
        this.plan = plan == null ? RecoveryPlan.START_NEW : plan;
        this.fencingToken = fencingToken;
        this.messageCutoff = messageCutoff;
        this.persistentFeatures = persistentFeatures == null ? Map.of() : Map.copyOf(persistentFeatures);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.faultInjector = faultInjector == null ? RecoveryFaultInjector.NONE : faultInjector;
    }

    public boolean enabled() {
        return store.enabled();
    }

    public RecoveryPlan plan() {
        return plan;
    }

    public long fencingToken() {
        return fencingToken;
    }

    public long messageCutoff() {
        return messageCutoff;
    }

    public Map<String, Object> persistentFeatures() {
        return persistentFeatures;
    }

    public void savePreTurn(RuntimeCheckpoint checkpoint) {
        faultInjector.at(RecoveryPoint.BEFORE_PRE_TURN_CHECKPOINT);
        store.saveCheckpoint(checkpoint, fencingToken, clock.instant());
        faultInjector.at(RecoveryPoint.AFTER_PRE_TURN_CHECKPOINT);
    }

    public void recordToolDecision(
            RuntimeCheckpoint checkpoint,
            List<ToolCallJournalEntry> calls) {
        store.recordToolDecision(checkpoint, calls, fencingToken, clock.instant());
        faultInjector.at(RecoveryPoint.AFTER_MODEL_DECISION_COMMIT);
    }

    public void markToolExecuting(
            String sessionId,
            String requestId,
            String attemptId,
            List<String> callIds) {
        store.markToolExecuting(
                sessionId, requestId, attemptId, callIds, fencingToken, clock.instant());
        faultInjector.at(RecoveryPoint.AFTER_TOOL_EXECUTING_COMMIT);
    }

    public void recordToolResult(ToolCallJournalEntry call) {
        store.recordToolResult(call, fencingToken, clock.instant());
        faultInjector.at(RecoveryPoint.AFTER_TOOL_RESULT_COMMIT);
    }

    public void savePostTurn(RuntimeCheckpoint checkpoint) {
        faultInjector.at(RecoveryPoint.BEFORE_POST_TURN_CHECKPOINT);
        store.saveCheckpoint(checkpoint, fencingToken, clock.instant());
        faultInjector.at(RecoveryPoint.AFTER_POST_TURN_CHECKPOINT);
    }

    public void saveTerminal(RuntimeCheckpoint checkpoint) {
        faultInjector.at(RecoveryPoint.BEFORE_TERMINAL_CHECKPOINT);
        store.saveCheckpoint(checkpoint, fencingToken, clock.instant());
        faultInjector.at(RecoveryPoint.AFTER_TERMINAL_CHECKPOINT);
    }
}
