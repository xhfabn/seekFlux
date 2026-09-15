package io.seekflux.platform.agentruntime.domain.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolReconciler;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolRegistrationPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.ToolExecutionPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.ToolExecutionObserver;
import io.seekflux.platform.agentruntime.domain.exception.UnsafeToolRecoveryException;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
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
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectStatus;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.service.execution.AgentCallGuard;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryPoint;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentRuntimeSideEffectLedgerTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    @AfterEach
    void shutdown() {
        executor.shutdownNow();
    }

    @Test
    void preparedCrashBeforeExternalRequestExecutesExactlyOnceOnRecovery() {
        Scenario scenario = scenario(true);

        assertThrows(SimulatedCrash.class, () -> scenario.firstRun(
                new CrashOnce(RecoveryPoint.AFTER_SIDE_EFFECT_PREPARED)));
        assertEquals(0, scenario.externalCreates.get());
        assertEquals(SideEffectStatus.PREPARED, scenario.store.onlyLedger().status());

        AgentRunResult result = scenario.recover(RecoveryFaultInjector.NONE);

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals(1, scenario.externalCreates.get());
        assertEquals(1, scenario.executeCalls.get());
        assertEquals(SideEffectStatus.SUCCEEDED, scenario.store.onlyLedger().status());
    }

    @Test
    void externalSuccessBeforeLocalConfirmationUsesReconciliationWithoutRepeatingMutation() {
        Scenario scenario = scenario(true);

        assertThrows(SimulatedCrash.class, () -> scenario.firstRun(
                new CrashOnce(RecoveryPoint.AFTER_MUTATING_TOOL_RETURN_BEFORE_LEDGER_RESULT)));
        assertEquals(1, scenario.externalCreates.get());
        assertEquals(SideEffectStatus.EXECUTING, scenario.store.onlyLedger().status());

        AgentRunResult result = scenario.recover(RecoveryFaultInjector.NONE);

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals(1, scenario.externalCreates.get());
        assertEquals(1, scenario.executeCalls.get());
        assertEquals(1, scenario.reconcileCalls.get());
        assertEquals(SideEffectStatus.RECONCILED, scenario.store.onlyLedger().status());
        assertEquals("receipt-1", scenario.store.onlyLedger().externalReceipt().get("receiptId"));
        assertTrue(scenario.events.stream().anyMatch(event ->
                event.source() == ToolExecutionObserver.Source.RECONCILIATION
                        && event.phase() == ToolExecutionObserver.Phase.AFTER));
    }

    @Test
    void ledgerSuccessBeforeJournalAndSessionProgressIsReusedDirectly() {
        Scenario scenario = scenario(true);

        assertThrows(SimulatedCrash.class, () -> scenario.firstRun(
                new CrashOnce(RecoveryPoint.AFTER_SIDE_EFFECT_RESULT_COMMIT)));
        assertEquals(SideEffectStatus.SUCCEEDED, scenario.store.onlyLedger().status());

        AgentRunResult result = scenario.recover(RecoveryFaultInjector.NONE);

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals(1, scenario.externalCreates.get());
        assertEquals(1, scenario.executeCalls.get());
        assertEquals(0, scenario.reconcileCalls.get());
    }

    @Test
    void repeatedRecoveryAfterReconciliationCommitNeverRepeatsExternalMutation() {
        Scenario scenario = scenario(true);
        assertThrows(SimulatedCrash.class, () -> scenario.firstRun(
                new CrashOnce(RecoveryPoint.AFTER_MUTATING_TOOL_RETURN_BEFORE_LEDGER_RESULT)));

        assertThrows(SimulatedCrash.class, () -> scenario.recover(
                new CrashOnce(RecoveryPoint.AFTER_SIDE_EFFECT_RESULT_COMMIT)));
        AgentRunResult result = scenario.recover(RecoveryFaultInjector.NONE);

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals(1, scenario.externalCreates.get());
        assertEquals(1, scenario.executeCalls.get());
        assertEquals(1, scenario.reconcileCalls.get());
        assertEquals(SideEffectStatus.RECONCILED, scenario.store.onlyLedger().status());
    }

    @Test
    void unknownMutatingCallWithoutReconcilerFailsClosedAndDoesNotRepeat() {
        Scenario scenario = scenario(false);
        assertThrows(SimulatedCrash.class, () -> scenario.firstRun(
                new CrashOnce(RecoveryPoint.AFTER_MUTATING_TOOL_RETURN_BEFORE_LEDGER_RESULT)));

        assertThrows(UnsafeToolRecoveryException.class, () ->
                scenario.recover(RecoveryFaultInjector.NONE));
        assertThrows(UnsafeToolRecoveryException.class, () ->
                scenario.recover(RecoveryFaultInjector.NONE));

        assertEquals(1, scenario.externalCreates.get());
        assertEquals(1, scenario.executeCalls.get());
        assertEquals(SideEffectStatus.UNKNOWN, scenario.store.onlyLedger().status());
    }

    @Test
    void exceptionAfterExternalWriteRemainsUnknownInsteadOfClaimingDefinitiveFailure() {
        AtomicInteger externalWrites = new AtomicInteger();
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "publish"; }
            @Override public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "publish-v1", Map.of("query", AgentToolParameter.requiredString(100)));
            }
            @Override public Effect effect() { return Effect.MUTATING; }
            @Override public AgentToolResult execute(AgentToolContext context) {
                externalWrites.incrementAndGet();
                throw new IllegalStateException("connection lost after write");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolRegistrationPolicy.ALLOW_MUTATING);
        InMemoryStore store = new InMemoryStore();
        AgentRuntime runtime = new AgentRuntime(
                registry, new DefaultAgentToolExecutor(registry), executor,
                AgentRunRecorder.NOOP, CLOCK);

        AgentRunResult result = runtime.run(
                definition(), request(), planner(new AtomicInteger()), new CancellationToken(),
                execution(store, RecoveryPlan.START_NEW, RecoveryFaultInjector.NONE));

        assertEquals(AgentTerminalState.FALLBACK_REQUIRED, result.state());
        assertEquals("MUTATING_TOOL_STATE_UNKNOWN", result.fallbackReason());
        assertEquals(1, externalWrites.get());
        assertEquals(SideEffectStatus.UNKNOWN, store.onlyLedger().status());
    }

    @Test
    void mutatingToolRequiresRegistrationPermissionAndRuntimeLedger() {
        AgentTool tool = new PlainMutatingTool(
                new AtomicInteger(), new AtomicInteger(), new HashMap<>());

        assertThrows(IllegalArgumentException.class, () ->
                new AgentToolRegistry(List.of(tool)));

        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolRegistrationPolicy.ALLOW_MUTATING);
        AgentRuntime runtime = new AgentRuntime(
                registry, new DefaultAgentToolExecutor(registry), executor,
                AgentRunRecorder.NOOP, CLOCK);
        assertThrows(IllegalStateException.class, () -> runtime.run(
                definition(), request(), planner(new AtomicInteger()), new CancellationToken(),
                AgentRecoveryExecution.DISABLED));
    }

    @Test
    void executionPolicyCanModifyDenyAndReserveApproval() {
        AtomicInteger calls = new AtomicInteger();
        AgentTool readOnly = readOnlyTool(calls);
        AgentToolRegistry registry = new AgentToolRegistry(List.of(readOnly));

        AgentRunResult modified = runtime(registry, context ->
                ToolExecutionPolicy.Decision.modify(Map.of("query", "sanitized"), "redacted"))
                .run(definition(), request(), context -> context.observations().isEmpty()
                        ? new AgentDecision.CallTool("publish", Map.of("query", "raw"))
                        : new AgentDecision.Complete(context.observations().getFirst().result().output()));
        assertEquals("sanitized", modified.output().get("query"));
        assertEquals(1, calls.get());

        AgentRunResult denied = runtime(registry, context ->
                ToolExecutionPolicy.Decision.deny("tenant policy"))
                .run(definition(), request(), ignored ->
                        new AgentDecision.CallTool("publish", Map.of("query", "raw")));
        assertEquals("TOOL_POLICY_DENIED", denied.fallbackReason());

        AgentRunResult approval = runtime(registry, context ->
                ToolExecutionPolicy.Decision.needApproval("human approval"))
                .run(definition(), request(), ignored ->
                        new AgentDecision.CallTool("publish", Map.of("query", "raw")));
        assertEquals("TOOL_APPROVAL_REQUIRED", approval.fallbackReason());
        assertEquals(1, calls.get());
    }

    private AgentRuntime runtime(AgentToolRegistry registry, ToolExecutionPolicy policy) {
        return new AgentRuntime(
                registry, new DefaultAgentToolExecutor(registry), executor,
                AgentRunRecorder.NOOP, CLOCK, AgentCallGuard.UNBOUNDED,
                policy, ToolExecutionObserver.NOOP);
    }

    private Scenario scenario(boolean reconciler) {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger executes = new AtomicInteger();
        AtomicInteger reconciles = new AtomicInteger();
        Map<String, AgentToolResult> external = new HashMap<>();
        AgentTool tool = reconciler
                ? new ReconcilingMutatingTool(creates, executes, reconciles, external)
                : new PlainMutatingTool(creates, executes, external);
        AgentToolRegistry registry = new AgentToolRegistry(
                List.of(tool), AgentToolRegistrationPolicy.ALLOW_MUTATING);
        List<ToolExecutionObserver.Event> events = new ArrayList<>();
        AgentRuntime runtime = new AgentRuntime(
                registry, new DefaultAgentToolExecutor(registry), executor,
                AgentRunRecorder.NOOP, CLOCK, AgentCallGuard.UNBOUNDED,
                ToolExecutionPolicy.ALLOW_ALL, events::add);
        return new Scenario(
                runtime, new InMemoryStore(), creates, executes, reconciles, events);
    }

    private final class Scenario {
        private final AgentRuntime runtime;
        private final InMemoryStore store;
        private final AtomicInteger externalCreates;
        private final AtomicInteger executeCalls;
        private final AtomicInteger reconcileCalls;
        private final List<ToolExecutionObserver.Event> events;
        private final AtomicInteger modelCalls = new AtomicInteger();

        private Scenario(
                AgentRuntime runtime,
                InMemoryStore store,
                AtomicInteger externalCreates,
                AtomicInteger executeCalls,
                AtomicInteger reconcileCalls,
                List<ToolExecutionObserver.Event> events) {
            this.runtime = runtime;
            this.store = store;
            this.externalCreates = externalCreates;
            this.executeCalls = executeCalls;
            this.reconcileCalls = reconcileCalls;
            this.events = events;
        }

        private AgentRunResult firstRun(RecoveryFaultInjector fault) {
            return runtime.run(definition(), request(), planner(modelCalls), new CancellationToken(),
                    execution(store, RecoveryPlan.START_NEW, fault));
        }

        private AgentRunResult recover(RecoveryFaultInjector fault) {
            RecoveryPlan plan = store.commitResume(resumeIngress(), 2, CLOCK.instant());
            return runtime.run(definition(), request(), planner(modelCalls), new CancellationToken(),
                    execution(store, plan, fault));
        }
    }

    private static class PlainMutatingTool implements AgentTool {
        private final AtomicInteger creates;
        private final AtomicInteger executes;
        private final Map<String, AgentToolResult> external;

        private PlainMutatingTool(
                AtomicInteger creates,
                AtomicInteger executes,
                Map<String, AgentToolResult> external) {
            this.creates = creates;
            this.executes = executes;
            this.external = external;
        }

        @Override public String name() { return "publish"; }

        @Override public AgentToolSchema schema() {
            return new AgentToolSchema(
                    "publish-v1", Map.of("query", AgentToolParameter.requiredString(100)));
        }

        @Override public Effect effect() { return Effect.MUTATING; }

        @Override
        public AgentToolResult execute(AgentToolContext context) {
            executes.incrementAndGet();
            AgentToolResult result = AgentToolResult.success(
                    Map.of("query", context.arguments().get("query")),
                    "external-trace",
                    Map.of("receiptId", "receipt-1"));
            if (external.putIfAbsent(context.idempotencyKey(), result) == null) {
                creates.incrementAndGet();
            }
            return result;
        }
    }

    private static final class ReconcilingMutatingTool
            extends PlainMutatingTool implements AgentToolReconciler {
        private final AtomicInteger reconciles;
        private final Map<String, AgentToolResult> external;

        private ReconcilingMutatingTool(
                AtomicInteger creates,
                AtomicInteger executes,
                AtomicInteger reconciles,
                Map<String, AgentToolResult> external) {
            super(creates, executes, external);
            this.reconciles = reconciles;
            this.external = external;
        }

        @Override
        public SideEffectReconciliation reconcile(
                SideEffectLedgerEntry ledgerEntry,
                AgentToolContext context) {
            reconciles.incrementAndGet();
            AgentToolResult result = external.get(ledgerEntry.idempotencyKey());
            return result == null
                    ? SideEffectReconciliation.unknown("EXTERNAL_STATUS_QUERY", "receipt not visible")
                    : SideEffectReconciliation.succeeded(result, "EXTERNAL_STATUS_QUERY");
        }
    }

    private static AgentTool readOnlyTool(AtomicInteger calls) {
        return new AgentTool() {
            @Override public String name() { return "publish"; }
            @Override public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "read-v1", Map.of("query", AgentToolParameter.requiredString(100)));
            }
            @Override public Effect effect() { return Effect.READ_ONLY; }
            @Override public AgentToolResult execute(AgentToolContext context) {
                calls.incrementAndGet();
                return AgentToolResult.success(
                        Map.of("query", context.arguments().get("query")), null);
            }
        };
    }

    private static AgentPlanner planner(AtomicInteger calls) {
        return context -> {
            calls.incrementAndGet();
            return context.observations().isEmpty()
                    ? new AgentDecision.CallTool("publish", Map.of("query", "payload"))
                    : new AgentDecision.Complete(Map.of("published", true));
        };
    }

    private static AgentDefinition definition() {
        return new AgentDefinition(
                "agent", "v1", "planner-v1", "prompt-v1", "provider-v1",
                Set.of("publish"), 3, 2, Duration.ofSeconds(2), true);
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest("request", "session", "turn", "publish", Map.of());
    }

    private static ResumeIngress resumeIngress() {
        return new ResumeIngress(1, "session", "request", "turn", ResumeSource.CRASH_RECOVERY);
    }

    private static AgentRecoveryExecution execution(
            InMemoryStore store,
            RecoveryPlan plan,
            RecoveryFaultInjector fault) {
        return new AgentRecoveryExecution(store, plan, 2, 2, Map.of(), CLOCK, fault);
    }

    private static final class CrashOnce implements RecoveryFaultInjector {
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

    private static final class InMemoryStore implements AgentRecoveryStore {
        private RuntimeCheckpoint checkpoint;
        private final List<ToolCallJournalEntry> calls = new ArrayList<>();
        private final Map<String, SideEffectLedgerEntry> ledger = new HashMap<>();

        @Override public boolean enabled() { return true; }
        @Override public boolean sideEffectLedgerEnabled() { return true; }

        @Override
        public RecoveryPlan commitResume(ResumeIngress ingress, long token, Instant time) {
            for (int index = 0; index < calls.size(); index++) {
                ToolCallJournalEntry call = calls.get(index);
                if (call.status() == ToolJournalStatus.EXECUTING) {
                    calls.set(index, call.withStatus(ToolJournalStatus.UNKNOWN, null, time));
                }
            }
            ledger.replaceAll((id, entry) -> entry.status() == SideEffectStatus.EXECUTING
                    ? entry.forAttempt(entry.attemptId(), SideEffectStatus.UNKNOWN,
                            null, null, null, null, time)
                    : entry);
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
            return new RecoveryPlan(
                    current.isEmpty() ? ResumeAction.RESUME_PRE_TURN : ResumeAction.RESUME_PENDING_TOOLS,
                    checkpoint,
                    current);
        }

        @Override public void saveCheckpoint(RuntimeCheckpoint value, long token, Instant time) {
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
                String sessionId,
                String requestId,
                String attemptId,
                List<String> ids,
                long token,
                Instant time) {
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
            throw new IllegalStateException("missing Tool journal");
        }

        @Override
        public SideEffectLedgerEntry prepareSideEffect(
                SideEffectLedgerEntry entry, long token, Instant time) {
            ledger.putIfAbsent(entry.toolCallId(), entry);
            SideEffectLedgerEntry existing = ledger.get(entry.toolCallId());
            if (!existing.requestDigest().equals(entry.requestDigest())) {
                throw new IllegalStateException("ledger digest conflict");
            }
            return existing;
        }

        @Override
        public SideEffectLedgerEntry markSideEffectExecuting(
                SideEffectLedgerEntry entry, long token, Instant time) {
            SideEffectLedgerEntry current = ledger.get(entry.toolCallId());
            if (current.status() != SideEffectStatus.PREPARED) {
                throw new IllegalStateException("side effect was already dispatched");
            }
            ledger.put(entry.toolCallId(), entry);
            return entry;
        }

        @Override
        public SideEffectLedgerEntry recordSideEffectResult(
                SideEffectLedgerEntry entry, long token, Instant time) {
            ledger.put(entry.toolCallId(), entry);
            return entry;
        }

        @Override
        public void markSideEffectsUnknown(
                String sessionId,
                String requestId,
                List<String> toolCallIds,
                long token,
                Instant time) {
            for (String id : toolCallIds) {
                SideEffectLedgerEntry entry = ledger.get(id);
                if (entry != null && entry.status() == SideEffectStatus.EXECUTING) {
                    ledger.put(id, entry.forAttempt(
                            entry.attemptId(), SideEffectStatus.UNKNOWN,
                            null, null, null, null, time));
                }
            }
        }

        @Override
        public Optional<SideEffectLedgerEntry> findSideEffect(String toolCallId) {
            return Optional.ofNullable(ledger.get(toolCallId));
        }

        private SideEffectLedgerEntry onlyLedger() {
            return ledger.values().stream().findFirst().orElseThrow();
        }
    }
}
