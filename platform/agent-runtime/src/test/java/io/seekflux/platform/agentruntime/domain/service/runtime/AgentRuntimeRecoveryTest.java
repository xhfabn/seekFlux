package io.seekflux.platform.agentruntime.domain.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.domain.model.recovery.CheckpointBoundary;
import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeAction;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeIngress;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeSource;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolJournalStatus;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryPoint;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentRuntimeRecoveryTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC);

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    @Test
    void resumesDecisionCommittedBeforeToolSubmissionWithoutRepeatingModel() {
        ScenarioResult result = recoverAfter(RecoveryPoint.AFTER_MODEL_DECISION_COMMIT);

        assertEquals(AgentTerminalState.RESULTS_READY, result.result().state());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void crashBeforePreTurnCheckpointRestartsFromWorkspaceBoundary() {
        ScenarioResult result = recoverAfter(RecoveryPoint.BEFORE_PRE_TURN_CHECKPOINT);

        assertEquals(AgentTerminalState.RESULTS_READY, result.result().state());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void crashAfterPreTurnCheckpointResumesBeforeModel() {
        ScenarioResult result = recoverAfter(RecoveryPoint.AFTER_PRE_TURN_CHECKPOINT);

        assertEquals(AgentTerminalState.RESULTS_READY, result.result().state());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void retriesUnknownReadOnlyToolAfterExecutingMarkerWithoutRepeatingModel() {
        ScenarioResult result = recoverAfter(RecoveryPoint.AFTER_TOOL_EXECUTING_COMMIT);

        assertEquals(AgentTerminalState.RESULTS_READY, result.result().state());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void reusesCompletedToolResultAfterCrashWithoutRepeatingSideEffect() {
        ScenarioResult result = recoverAfter(RecoveryPoint.AFTER_TOOL_RESULT_COMMIT);

        assertEquals(AgentTerminalState.RESULTS_READY, result.result().state());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void resumesPostTurnCheckpointWithoutRepeatingTool() {
        ScenarioResult result = recoverAfter(RecoveryPoint.AFTER_POST_TURN_CHECKPOINT);

        assertEquals(AgentTerminalState.RESULTS_READY, result.result().state());
        assertEquals(2, result.modelCalls());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void terminalCheckpointCanBeCommittedWithoutRepeatingModelOrTool() {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AgentRuntime runtime = runtime(toolCalls, AgentTool.Effect.READ_ONLY);
        AgentPlanner planner = planner(modelCalls);
        CrashOnce crash = new CrashOnce(RecoveryPoint.AFTER_TERMINAL_CHECKPOINT);

        assertThrows(SimulatedCrash.class, () -> runtime.run(
                definition(), request(), planner, new CancellationToken(),
                execution(store, RecoveryPlan.START_NEW, crash)));
        RecoveryPlan plan = store.commitResume(resumeIngress(), 2, CLOCK.instant());
        assertEquals(ResumeAction.COMMIT_TERMINAL, plan.action());

        AgentRunResult recovered = runtime.run(
                definition(), request(), planner, new CancellationToken(),
                execution(store, plan, point -> { }));

        assertEquals(AgentTerminalState.RESULTS_READY, recovered.state());
        assertEquals(2, modelCalls.get());
        assertEquals(1, toolCalls.get());
    }

    @Test
    void crashBeforeTerminalCheckpointRepeatsOnlyTheFinalModelTurn() {
        ScenarioResult result = recoverAfter(RecoveryPoint.BEFORE_TERMINAL_CHECKPOINT);

        assertEquals(
                AgentTerminalState.RESULTS_READY,
                result.result().state(),
                result.result().fallbackReason());
        assertEquals(3, result.modelCalls());
        assertEquals(1, result.toolCalls());
    }

    @Test
    void unknownMutatingToolProducesFailClosedResumeAction() {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        AgentRuntime runtime = runtime(new AtomicInteger(), AgentTool.Effect.MUTATING);
        CrashOnce crash = new CrashOnce(RecoveryPoint.AFTER_TOOL_EXECUTING_COMMIT);

        assertThrows(SimulatedCrash.class, () -> runtime.run(
                definition(), request(), planner(new AtomicInteger()), new CancellationToken(),
                execution(store, RecoveryPlan.START_NEW, crash)));

        RecoveryPlan plan = store.commitResume(resumeIngress(), 2, CLOCK.instant());

        assertEquals(ResumeAction.FAIL_UNSAFE_PENDING_TOOL, plan.action());
    }

    @Test
    void recoveredCancelledToolFinishesCancelledInsteadOfContinuingModelLoop() {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AgentRuntime runtime = runtime(toolCalls, AgentTool.Effect.READ_ONLY);

        assertThrows(SimulatedCrash.class, () -> runtime.run(
                definition(), request(), planner(modelCalls), new CancellationToken(),
                execution(store, RecoveryPlan.START_NEW,
                        new CrashOnce(RecoveryPoint.AFTER_MODEL_DECISION_COMMIT))));
        ToolCallJournalEntry decided = store.calls.getFirst();
        AgentToolObservation cancelled = new AgentToolObservation(
                decided.toolCallId(), decided.toolName(), decided.toolSchemaVersion(),
                decided.arguments(), decided.argumentsRepaired(),
                AgentToolResult.failure(CancellationCause.USER_CANCEL.name()), 0);
        store.calls.set(0, decided.withStatus(
                ToolJournalStatus.CANCELLED, cancelled, CLOCK.instant()));

        RecoveryPlan plan = store.commitResume(resumeIngress(), 2, CLOCK.instant());
        AgentRunResult recovered = runtime.run(
                definition(), request(), planner(modelCalls), new CancellationToken(),
                execution(store, plan, ignored -> { }));

        assertEquals(AgentTerminalState.CANCELLED, recovered.state());
        assertEquals(CancellationCause.USER_CANCEL.name(), recovered.cancellationReason());
        assertEquals(1, modelCalls.get());
        assertEquals(0, toolCalls.get());
    }

    private ScenarioResult recoverAfter(RecoveryPoint point) {
        InMemoryRecoveryStore store = new InMemoryRecoveryStore();
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AgentRuntime runtime = runtime(toolCalls, AgentTool.Effect.READ_ONLY);
        AgentPlanner planner = planner(modelCalls);

        assertThrows(SimulatedCrash.class, () -> runtime.run(
                definition(), request(), planner, new CancellationToken(),
                execution(store, RecoveryPlan.START_NEW, new CrashOnce(point))));
        RecoveryPlan plan = store.commitResume(resumeIngress(), 2, CLOCK.instant());
        AgentRunResult recovered = runtime.run(
                definition(), request(), planner, new CancellationToken(),
                execution(store, plan, ignored -> { }));
        return new ScenarioResult(recovered, modelCalls.get(), toolCalls.get());
    }

    private AgentRuntime runtime(AtomicInteger toolCalls, AgentTool.Effect effect) {
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "search"; }
            @Override public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "search-v1", Map.of("query", AgentToolParameter.requiredString(100)));
            }
            @Override public Effect effect() { return effect; }
            @Override public AgentToolResult execute(AgentToolContext context) {
                toolCalls.incrementAndGet();
                return AgentToolResult.success(Map.of("answer", "ok"), "trace-1");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool),
                effect == AgentTool.Effect.MUTATING
                        ? AgentToolRegistrationPolicy.ALLOW_MUTATING
                        : AgentToolRegistrationPolicy.SAFE_ONLY);
        return new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                CLOCK);
    }

    private static AgentPlanner planner(AtomicInteger calls) {
        return context -> {
            calls.incrementAndGet();
            return context.observations().isEmpty()
                    ? new AgentDecision.CallTool("search", Map.of("query", "camp"))
                    : new AgentDecision.Complete(Map.of("answer", "done"));
        };
    }

    private static AgentDefinition definition() {
        return new AgentDefinition(
                "agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                Set.of("search"), 3, 2, Duration.ofSeconds(2), true);
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest("request", "session", "turn", "input", Map.of());
    }

    private static ResumeIngress resumeIngress() {
        return new ResumeIngress(1, "session", "request", "turn", ResumeSource.CRASH_RECOVERY);
    }

    private static AgentRecoveryExecution execution(
            InMemoryRecoveryStore store,
            RecoveryPlan plan,
            io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector fault) {
        return new AgentRecoveryExecution(store, plan, 2, 2, Map.of("goal", "camp"), CLOCK, fault);
    }

    private record ScenarioResult(AgentRunResult result, int modelCalls, int toolCalls) { }

    private static final class CrashOnce
            implements io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector {
        private final RecoveryPoint target;
        private boolean crashed;

        private CrashOnce(RecoveryPoint target) {
            this.target = target;
        }

        @Override
        public void at(RecoveryPoint point) {
            if (!crashed && point == target) {
                crashed = true;
                throw new SimulatedCrash();
            }
        }
    }

    private static final class SimulatedCrash extends RuntimeException { }

    private static final class InMemoryRecoveryStore implements AgentRecoveryStore {
        private RuntimeCheckpoint checkpoint;
        private final List<ToolCallJournalEntry> calls = new ArrayList<>();

        @Override public boolean enabled() { return true; }

        @Override public boolean sideEffectLedgerEnabled() { return true; }

        @Override
        public SideEffectLedgerEntry prepareSideEffect(
                SideEffectLedgerEntry entry, long token, Instant time) {
            return entry;
        }

        @Override
        public RecoveryPlan commitResume(ResumeIngress ingress, long token, Instant time) {
            for (int index = 0; index < calls.size(); index++) {
                ToolCallJournalEntry call = calls.get(index);
                if (call.status() == ToolJournalStatus.EXECUTING) {
                    calls.set(index, call.withStatus(ToolJournalStatus.UNKNOWN, null, time));
                }
            }
            if (checkpoint == null) return RecoveryPlan.START_NEW;
            if (checkpoint.terminal()) {
                return new RecoveryPlan(ResumeAction.COMMIT_TERMINAL, checkpoint, calls);
            }
            if (checkpoint.boundary() == CheckpointBoundary.POST_TURN) {
                return new RecoveryPlan(ResumeAction.RESUME_POST_TURN, checkpoint, List.of());
            }
            List<ToolCallJournalEntry> current = calls.stream()
                    .filter(call -> call.step() == checkpoint.nextStep())
                    .toList();
            if (current.stream().anyMatch(call ->
                    call.status() == ToolJournalStatus.UNKNOWN && !call.safeToRetry())) {
                return new RecoveryPlan(
                        ResumeAction.FAIL_UNSAFE_PENDING_TOOL, checkpoint, current);
            }
            return new RecoveryPlan(
                    current.isEmpty()
                            ? ResumeAction.RESUME_PRE_TURN
                            : ResumeAction.RESUME_PENDING_TOOLS,
                    checkpoint,
                    current);
        }

        @Override
        public void saveCheckpoint(RuntimeCheckpoint value, long token, Instant time) {
            checkpoint = value;
        }

        @Override
        public void recordToolDecision(
                RuntimeCheckpoint value,
                List<ToolCallJournalEntry> values,
                long token,
                Instant time) {
            checkpoint = value;
            calls.clear();
            calls.addAll(values);
        }

        @Override
        public void markToolExecuting(
                String sessionId, String requestId, String attemptId,
                List<String> ids, long token, Instant time) {
            for (int index = 0; index < calls.size(); index++) {
                ToolCallJournalEntry call = calls.get(index);
                if (ids.contains(call.toolCallId())) {
                    calls.set(index, call.forAttempt(
                            attemptId, ToolJournalStatus.EXECUTING, null, time));
                }
            }
        }

        @Override
        public void recordToolResult(ToolCallJournalEntry value, long token, Instant time) {
            for (int index = 0; index < calls.size(); index++) {
                if (calls.get(index).toolCallId().equals(value.toolCallId())) {
                    calls.set(index, value);
                    return;
                }
            }
            throw new IllegalStateException("missing Tool journal entry");
        }
    }
}
