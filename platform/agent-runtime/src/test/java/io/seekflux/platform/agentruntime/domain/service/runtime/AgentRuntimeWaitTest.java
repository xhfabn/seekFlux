package io.seekflux.platform.agentruntime.domain.service.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.ToolExecutionPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentRecoveryStore;
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
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolParameter;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolSchema;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolutionResult;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitRequest;
import io.seekflux.platform.agentruntime.domain.service.execution.AgentCallGuard;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import io.seekflux.platform.agentruntime.domain.service.recovery.RecoveryFaultInjector;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.infrastructure.tool.DefaultAgentToolExecutor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AgentRuntimeWaitTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    @Test
    void approvalSuspendsBeforeToolAndResumesOriginalCallExactlyOnce() {
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        MemoryWaitStore store = new MemoryWaitStore();
        AgentRuntime runtime = runtime(toolCalls);
        AgentPlanner planner = planner(modelCalls);

        AgentRunResult waiting = runtime.run(
                definition(), request(), planner, new CancellationToken(),
                execution(store, RecoveryPlan.START_NEW));

        assertEquals(AgentTerminalState.WAITING, waiting.state());
        assertNotNull(waiting.waitState());
        assertEquals(NOW.plus(Duration.ofMinutes(15)), waiting.waitState().deadlineAt());
        assertEquals(0, toolCalls.get());
        assertEquals(ToolJournalStatus.WAITING, store.call.status());

        WaitResolution approved = resolution(
                waiting.waitState(), "resolution-1", WaitResolution.Outcome.APPROVED, null);
        assertEquals(
                WaitResolutionResult.Status.COMMITTED,
                store.resolveWait(approved, 2, NOW).status());
        assertEquals(
                WaitResolutionResult.Status.DUPLICATE,
                store.resolveWait(approved, 2, NOW).status());

        RecoveryPlan plan = store.commitResume(resumeIngress(), 2, NOW);
        assertEquals(ResumeAction.RESUME_PENDING_TOOLS, plan.action());
        AgentRunResult completed = runtime.run(
                definition(), request(), planner, new CancellationToken(), execution(store, plan));

        assertEquals(AgentTerminalState.RESULTS_READY, completed.state());
        assertEquals(1, toolCalls.get());
        assertEquals(2, modelCalls.get());
    }

    @Test
    void timeoutWinsAndLateApprovalConflictsWithoutExecutingTool() {
        AtomicInteger toolCalls = new AtomicInteger();
        MemoryWaitStore store = new MemoryWaitStore();
        AgentRuntime runtime = runtime(toolCalls);
        AgentRunResult waiting = runtime.run(
                definition(), request(), planner(new AtomicInteger()), new CancellationToken(),
                execution(store, RecoveryPlan.START_NEW));

        WaitResolution timedOut = resolution(
                waiting.waitState(), "timeout-1", WaitResolution.Outcome.TIMED_OUT,
                "WAIT_TIMEOUT");
        assertEquals(
                WaitResolutionResult.Status.COMMITTED,
                store.resolveWait(timedOut, 2, waiting.waitState().deadlineAt()).status());
        assertEquals(
                WaitResolutionResult.Status.CONFLICT,
                store.resolveWait(resolution(
                        waiting.waitState(), "late-approval",
                        WaitResolution.Outcome.APPROVED, null), 3, NOW).status());

        RecoveryPlan plan = store.commitResume(resumeIngress(), 3, NOW);
        AgentRunResult result = runtime.run(
                definition(), request(), planner(new AtomicInteger()), new CancellationToken(),
                execution(store, plan));

        assertEquals(AgentTerminalState.FALLBACK_REQUIRED, result.state());
        assertEquals("WAIT_TIMEOUT", result.fallbackReason());
        assertEquals(0, toolCalls.get());
    }

    @Test
    void asyncToolCallbackBecomesRecoveredToolResultWithoutRepeatingDispatch() {
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger modelCalls = new AtomicInteger();
        MemoryWaitStore store = new MemoryWaitStore();
        AgentRuntime runtime = asyncRuntime(toolCalls);

        AgentRunResult waiting = runtime.run(
                definition(), request(), planner(modelCalls), new CancellationToken(),
                execution(store, RecoveryPlan.START_NEW));

        assertEquals(AgentTerminalState.WAITING, waiting.state());
        assertEquals(WaitState.WaitType.ASYNC_TASK, waiting.waitState().type());
        assertEquals(NOW.plusSeconds(5), waiting.waitState().deadlineAt());
        assertEquals(1, toolCalls.get());

        WaitResolution completed = new WaitResolution(
                1, "async-resolution", waiting.waitState().waitId(), "session", "request",
                "turn", WaitResolution.Outcome.COMPLETED, Map.of("answer", "async-ok"),
                null, NOW);
        assertEquals(
                WaitResolutionResult.Status.COMMITTED,
                store.resolveWait(completed, 2, NOW).status());

        AgentRunResult result = runtime.run(
                definition(), request(), planner(modelCalls), new CancellationToken(),
                execution(store, store.commitResume(resumeIngress(), 2, NOW)));

        assertEquals(AgentTerminalState.RESULTS_READY, result.state());
        assertEquals(1, toolCalls.get());
        assertEquals(2, modelCalls.get());
    }

    private AgentRuntime runtime(AtomicInteger toolCalls) {
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "publish"; }
            @Override public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "publish-v1", Map.of("query", AgentToolParameter.requiredString(100)));
            }
            @Override public Effect effect() { return Effect.READ_ONLY; }
            @Override public AgentToolResult execute(AgentToolContext context) {
                toolCalls.incrementAndGet();
                return AgentToolResult.success(Map.of("answer", "ok"), "trace-tool");
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        return new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                CLOCK,
                AgentCallGuard.UNBOUNDED,
                ignored -> ToolExecutionPolicy.Decision.needApproval("approve publish"),
                event -> { });
    }

    private AgentRuntime asyncRuntime(AtomicInteger toolCalls) {
        AgentTool tool = new AgentTool() {
            @Override public String name() { return "publish"; }
            @Override public AgentToolSchema schema() {
                return new AgentToolSchema(
                        "publish-v1", Map.of("query", AgentToolParameter.requiredString(100)));
            }
            @Override public Effect effect() { return Effect.IDEMPOTENT; }
            @Override public AgentToolResult execute(AgentToolContext context) {
                toolCalls.incrementAndGet();
                return AgentToolResult.waiting(new WaitRequest.AsyncTask(
                        "task-1", "webhook", Duration.ofSeconds(5)));
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tool));
        return new AgentRuntime(
                registry,
                new DefaultAgentToolExecutor(registry),
                executor,
                AgentRunRecorder.NOOP,
                CLOCK,
                AgentCallGuard.UNBOUNDED,
                ToolExecutionPolicy.ALLOW_ALL,
                event -> { });
    }

    private static AgentPlanner planner(AtomicInteger calls) {
        return context -> {
            calls.incrementAndGet();
            return context.observations().isEmpty()
                    ? new AgentDecision.CallTool("publish", Map.of("query", "camp"))
                    : new AgentDecision.Complete(Map.of("answer", "done"));
        };
    }

    private static AgentDefinition definition() {
        return new AgentDefinition(
                "agent", "v1", "loop-v1", "prompt-v1", "provider-v1",
                Set.of("publish"), 3, 2, Duration.ofSeconds(10), true);
    }

    private static AgentRunRequest request() {
        return new AgentRunRequest("request", "session", "turn", "input", Map.of());
    }

    private static ResumeIngress resumeIngress() {
        return new ResumeIngress(1, "session", "request", "turn", ResumeSource.HITL_CALLBACK);
    }

    private static WaitResolution resolution(
            WaitState state,
            String resolutionId,
            WaitResolution.Outcome outcome,
            String errorCode) {
        return new WaitResolution(
                1, resolutionId, state.waitId(), state.sessionId(), state.requestId(),
                state.turnId(), outcome, Map.of(), errorCode, NOW);
    }

    private static AgentRecoveryExecution execution(
            MemoryWaitStore store, RecoveryPlan plan) {
        return new AgentRecoveryExecution(
                store, plan, 2, 1, Map.of(), CLOCK, RecoveryFaultInjector.NONE);
    }

    private static final class MemoryWaitStore implements AgentRecoveryStore {
        private RuntimeCheckpoint checkpoint;
        private ToolCallJournalEntry call;
        private WaitState waitState;
        private WaitResolution resolution;

        @Override public boolean enabled() { return true; }

        @Override
        public void saveCheckpoint(RuntimeCheckpoint value, long token, Instant time) {
            checkpoint = value;
        }

        @Override
        public void recordToolDecision(
                RuntimeCheckpoint value,
                List<ToolCallJournalEntry> calls,
                long token,
                Instant time) {
            checkpoint = value;
            call = calls.getFirst();
        }

        @Override
        public void suspendWait(
                RuntimeCheckpoint value,
                WaitState state,
                ToolCallJournalEntry waitingCall,
                long token,
                Instant time) {
            checkpoint = value;
            waitState = state;
            call = waitingCall;
        }

        @Override
        public WaitResolutionResult resolveWait(
                WaitResolution candidate, long token, Instant time) {
            if (waitState == null || !waitState.waitId().equals(candidate.waitId())) {
                return WaitResolutionResult.missing();
            }
            if (resolution != null) {
                return resolution.equals(candidate)
                        ? WaitResolutionResult.duplicate(waitState, resolution)
                        : WaitResolutionResult.conflict(waitState, resolution);
            }
            resolution = candidate;
            if (candidate.outcome() == WaitResolution.Outcome.APPROVED) {
                call = call.withStatus(ToolJournalStatus.DECIDED, null, time);
            } else if (candidate.outcome() == WaitResolution.Outcome.COMPLETED) {
                AgentToolObservation observation = new AgentToolObservation(
                        call.toolCallId(), call.toolName(), call.toolSchemaVersion(),
                        call.arguments(), call.argumentsRepaired(),
                        AgentToolResult.success(candidate.output(), null), 0);
                call = call.withStatus(ToolJournalStatus.SUCCEEDED, observation, time);
            } else {
                String code = candidate.outcome() == WaitResolution.Outcome.TIMED_OUT
                        ? candidate.errorCode() : "TOOL_APPROVAL_DENIED";
                AgentToolObservation observation = new AgentToolObservation(
                        call.toolCallId(), call.toolName(), call.toolSchemaVersion(),
                        call.arguments(), call.argumentsRepaired(), AgentToolResult.failure(code), 0);
                ToolJournalStatus status = candidate.outcome() == WaitResolution.Outcome.TIMED_OUT
                        ? ToolJournalStatus.TIMED_OUT : ToolJournalStatus.FAILED;
                call = call.withStatus(status, observation, time);
            }
            return WaitResolutionResult.committed(waitState, candidate);
        }

        @Override
        public RecoveryPlan commitResume(ResumeIngress ingress, long token, Instant time) {
            if (checkpoint == null) {
                return RecoveryPlan.START_NEW;
            }
            if (resolution == null) {
                return new RecoveryPlan(ResumeAction.WAIT_FOR_EXTERNAL, checkpoint, List.of(call));
            }
            return new RecoveryPlan(
                    ResumeAction.RESUME_PENDING_TOOLS, checkpoint, List.of(call));
        }

        @Override
        public void markToolExecuting(
                String sessionId,
                String requestId,
                String attemptId,
                List<String> toolCallIds,
                long token,
                Instant time) {
            call = call.forAttempt(attemptId, ToolJournalStatus.EXECUTING, null, time);
        }

        @Override
        public void recordToolResult(
                ToolCallJournalEntry value, long token, Instant time) {
            call = value;
        }
    }
}
