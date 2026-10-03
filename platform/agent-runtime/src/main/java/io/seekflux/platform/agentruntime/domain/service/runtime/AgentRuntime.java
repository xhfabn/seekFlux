package io.seekflux.platform.agentruntime.domain.service.runtime;

import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.AgentToolExecutor;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.ToolExecutionObserver;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.EagerToolDispatcher;
import io.seekflux.platform.agentruntime.domain.model.agent.definition.AgentDefinition;
import io.seekflux.platform.agentruntime.application.spi.business.planner.AgentPlanner;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunEvent;
import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.model.run.AgentRunTrace;
import io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.message.AgentAssistantContent;
import io.seekflux.platform.agentruntime.domain.model.message.AgentMessage;
import io.seekflux.platform.agentruntime.domain.model.recovery.CheckpointBoundary;
import io.seekflux.platform.agentruntime.domain.model.recovery.RecoveryPlan;
import io.seekflux.platform.agentruntime.domain.model.recovery.ResumeAction;
import io.seekflux.platform.agentruntime.domain.model.recovery.RuntimeCheckpoint;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolCallJournalEntry;
import io.seekflux.platform.agentruntime.domain.model.recovery.ToolJournalStatus;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentTool;
import io.seekflux.platform.agentruntime.application.spi.business.tool.AgentToolReconciler;
import io.seekflux.platform.agentruntime.application.spi.business.tool.ToolExecutionPolicy;
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolInvocation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectLedgerEntry;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectReconciliation;
import io.seekflux.platform.agentruntime.domain.model.sideeffect.SideEffectStatus;
import io.seekflux.platform.agentruntime.domain.exception.UnsafeToolRecoveryException;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitRequest;
import io.seekflux.platform.agentruntime.domain.service.execution.AgentCallGuard;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import io.seekflux.platform.agentruntime.domain.model.capability.CapabilitySnapshot;
import io.seekflux.platform.agentruntime.domain.service.capability.CapabilityResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AgentRuntime {

    private static final long CANCELLATION_CHECK_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private final AgentToolRegistry tools;
    private final AgentToolExecutor toolExecutor;
    private final ExecutorService executor;
    private final AgentRunRecorder recorder;
    private final Clock clock;
    private final AgentCallGuard callGuard;
    private final ToolExecutionPolicy toolPolicy;
    private final ToolExecutionObserver toolObserver;
    private final CapabilityResolver capabilityResolver;

    public AgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            ExecutorService executor,
            AgentRunRecorder recorder,
            Clock clock) {
        this(tools, toolExecutor, executor, recorder, clock, AgentCallGuard.UNBOUNDED);
    }

    public AgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            ExecutorService executor,
            AgentRunRecorder recorder,
            Clock clock,
            AgentCallGuard callGuard) {
        this(tools, toolExecutor, executor, recorder, clock, callGuard,
                ToolExecutionPolicy.ALLOW_ALL, ToolExecutionObserver.NOOP,
                CapabilityResolver.legacy());
    }

    public AgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            ExecutorService executor,
            AgentRunRecorder recorder,
            Clock clock,
            AgentCallGuard callGuard,
            ToolExecutionPolicy toolPolicy,
            ToolExecutionObserver toolObserver) {
        this(tools, toolExecutor, executor, recorder, clock, callGuard, toolPolicy,
                toolObserver, CapabilityResolver.legacy());
    }

    public AgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            ExecutorService executor,
            AgentRunRecorder recorder,
            Clock clock,
            AgentCallGuard callGuard,
            ToolExecutionPolicy toolPolicy,
            ToolExecutionObserver toolObserver,
            CapabilityResolver capabilityResolver) {
        this.tools = Objects.requireNonNull(tools, "tool registry must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "tool executor must not be null");
        this.executor = Objects.requireNonNull(executor, "agent executor must not be null");
        this.recorder = Objects.requireNonNull(recorder, "run recorder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.callGuard = Objects.requireNonNull(callGuard, "call guard must not be null");
        this.toolPolicy = Objects.requireNonNull(toolPolicy, "Tool policy must not be null");
        this.toolObserver = Objects.requireNonNull(toolObserver, "Tool observer must not be null");
        this.capabilityResolver = Objects.requireNonNull(
                capabilityResolver, "capability resolver must not be null");
    }

    public AgentRunResult run(
            AgentDefinition definition,
            AgentRunRequest request,
            AgentPlanner planner) {
        return run(definition, request, planner, new CancellationToken());
    }

    public AgentRunResult run(
            AgentDefinition definition,
            AgentRunRequest request,
            AgentPlanner planner,
            CancellationToken cancellationToken) {
        return run(definition, request, planner, cancellationToken, AgentRecoveryExecution.DISABLED);
    }

    public AgentRunResult run(
            AgentDefinition definition,
            AgentRunRequest request,
            AgentPlanner planner,
            CancellationToken cancellationToken,
            AgentRecoveryExecution recovery) {
        return run(definition, request, planner, cancellationToken, recovery,
                PushEventPublisher.NOOP, legacyCapabilities(definition, request));
    }

    public AgentRunResult run(
            AgentDefinition definition,
            AgentRunRequest request,
            AgentPlanner planner,
            CancellationToken cancellationToken,
            AgentRecoveryExecution recovery,
            PushEventPublisher publisher) {
        return run(definition, request, planner, cancellationToken, recovery, publisher,
                legacyCapabilities(definition, request));
    }

    public AgentRunResult run(
            AgentDefinition definition,
            AgentRunRequest request,
            AgentPlanner planner,
            CancellationToken cancellationToken,
            AgentRecoveryExecution recovery,
            PushEventPublisher publisher,
            CapabilitySnapshot initialCapabilities) {
        Objects.requireNonNull(definition, "agent definition must not be null");
        Objects.requireNonNull(request, "agent run request must not be null");
        Objects.requireNonNull(planner, "agent planner must not be null");
        Objects.requireNonNull(cancellationToken, "cancellation token must not be null");
        Objects.requireNonNull(recovery, "recovery execution must not be null");
        PushEventPublisher delegate = publisher == null ? PushEventPublisher.NOOP : publisher;
        publisher = event -> {
            try {
                return delegate.publish(event);
            } catch (RuntimeException ignored) {
                return -1;
            }
        };

        RecoveryPlan recoveryPlan = recovery.plan();
        if (recoveryPlan.action() == ResumeAction.COMMIT_TERMINAL
                || recoveryPlan.action() == ResumeAction.WAIT_FOR_EXTERNAL) {
            AgentRunResult terminal = recoveryPlan.checkpoint().terminalResult();
            publisher.publish(new PushEvent.SegmentStarted(
                    terminal.trace().agentRunId(), clock.instant(),
                    request.requestId(), request.turnId()));
            publisher.publish(new PushEvent.LoopStarted(
                    terminal.trace().agentRunId(), clock.instant(), definition.id()));
            return terminal;
        }
        if (recoveryPlan.action() == ResumeAction.FAIL_UNSAFE_PENDING_TOOL) {
            throw new IllegalStateException("unsafe pending Tool must be rejected before Loop dispatch");
        }
        String runId = UUID.randomUUID().toString();
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        RuntimeCheckpoint restored = recoveryPlan.checkpoint();
        long budgetMillis = restored == null
                ? definition.timeout().toMillis()
                : restored.remainingBudgetMillis();
        long deadlineNanos = saturatingAdd(startedNanos, Duration.ofMillis(budgetMillis).toNanos());
        CapabilitySnapshot capabilities = initialCapabilities == null
                ? legacyCapabilities(definition, request) : initialCapabilities;
        AgentRunTrace.DefinitionSnapshot snapshot = definitionSnapshot(definition, capabilities);
        if (restored != null) {
            validateCheckpoint(restored, request, definition);
            capabilityResolver.validateRestorable(restored.definition().capabilities());
            snapshot = restored.definition();
            capabilities = snapshot.capabilities();
            if (!snapshot.toolSchemaVersions().keySet().containsAll(capabilities.registeredTools())) {
                throw new IllegalStateException("checkpoint Tool Schema versions are incomplete");
            }
            for (Map.Entry<String, String> frozen : snapshot.toolSchemaVersions().entrySet()) {
                tools.require(frozen.getKey(), frozen.getValue());
            }
        }
        if (tools.containsMutating(
                capabilities.effectiveTools(), snapshot.toolSchemaVersions())
                && !recovery.sideEffectLedgerEnabled()) {
            throw new IllegalStateException(
                    "MUTATING Tool requires a configured side-effect ledger");
        }
        RunState run = new RunState(
                runId, request, snapshot, startedAt, startedNanos, deadlineNanos,
                recovery, restored, publisher);
        run.record(AgentRunEvent.Type.RUN_STARTED, Map.of("definition", snapshot));
        run.publish(new PushEvent.SegmentStarted(
                runId, clock.instant(), request.requestId(), request.turnId()));
        run.publish(new PushEvent.LoopStarted(
                runId, clock.instant(), definition.id()));

        CancellationCause initialCancellation = cancellationToken.cause();
        if (initialCancellation != null) {
            return finishCancelled(run, initialCancellation);
        }

        List<AgentToolObservation> observations = new ArrayList<>(
                restored == null ? List.of() : restored.observations());
        Set<String> completedInvocations = new HashSet<>(
                restored == null ? Set.of() : restored.completedInvocations());
        int toolCalls = restored == null ? 0 : restored.toolCallCount();
        int startStep = restored == null ? 1 : restored.nextStep();

        if (recoveryPlan.action() == ResumeAction.RESUME_PENDING_TOOLS) {
            ResumeBatch resumed = resumePendingTools(
                    run,
                    recoveryPlan.toolCalls(),
                    observations,
                    completedInvocations,
                    deadlineNanos,
                    cancellationToken);
            toolCalls = Math.max(toolCalls, resumed.toolCallCount());
            startStep = resumed.nextStep();
            if (resumed.cancellationCause() != null) {
                return finishCancelled(run, resumed.cancellationCause());
            }
            if (resumed.failureCode() != null) {
                return finishFailure(run, definition, resumed.failureCode(), null);
            }
            run.savePostTurn(
                    startStep,
                    toolCalls,
                    observations,
                    completedInvocations);
        }

        for (int step = startStep; step <= definition.maxSteps(); step++) {
            CancellationCause stepCancellation = cancellationToken.cause();
            if (stepCancellation != null) {
                return finishCancelled(run, stepCancellation);
            }
            if (remainingNanos(deadlineNanos) <= 0) {
                return finishFailure(run, definition, "AGENT_DEADLINE_EXCEEDED", null);
            }

            Set<String> effectiveTools = run.capabilities().effectiveTools();
            RuntimeCheckpoint preTurnCheckpoint = run.savePreTurn(
                    step,
                    toolCalls,
                    observations,
                    completedInvocations);

            AgentDecision decision;
            long decisionStarted = System.nanoTime();
            int currentStep = step;
            EagerToolBatch eager = new EagerToolBatch(
                    run, request, effectiveTools, step, deadlineNanos, cancellationToken);
            try {
                AgentDecisionContext context = new AgentDecisionContext(
                        request,
                        step,
                        Duration.ofNanos(remainingNanos(deadlineNanos)),
                        observations,
                        run::recordUsage,
                        content -> run.recordAssistantContent(currentStep, content),
                        run.runId,
                        eager,
                        run.capabilities(),
                        run.snapshot.toolSchemaVersions(),
                        run.messages);
                decision = invoke(
                        () -> callGuard.execute(AgentCallGuard.CallType.MODEL, () -> planner.decide(context)),
                        deadlineNanos,
                        cancellationToken);
            } catch (CallFailure failure) {
                eager.cancelAll();
                String stepStatus = failure.cancellationCause == null ? "FAILED" : "CANCELLED";
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "PLAN", stepStatus, null, null, null,
                        elapsedMillis(decisionStarted), failure.code));
                run.record(AgentRunEvent.Type.DECISION_MADE, Map.of(
                        "step", step,
                        "status", stepStatus,
                        "errorCode", failure.code));
                if (failure.cancellationCause != null) {
                    return finishCancelled(run, failure.cancellationCause);
                }
                return finishFailure(run, definition, failure.code, null);
            }

            CancellationCause decisionCancellation = cancellationToken.cause();
            if (decisionCancellation != null) {
                eager.cancelAll();
                return finishCancelled(run, decisionCancellation);
            }
            if (decision == null) {
                eager.cancelAll();
                return finishFailure(run, definition, "PLANNER_RETURNED_NULL", null);
            }
            run.record(AgentRunEvent.Type.DECISION_MADE, decisionPayload(step, decision));

            if (decision instanceof AgentDecision.Complete complete) {
                eager.cancelAll();
                run.recordAssistant(step, decision, List.of());
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "COMPLETE", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), null));
                return finish(run, AgentTerminalState.RESULTS_READY, complete.output(), null,
                        null, false, "AGENT");
            }
            if (decision instanceof AgentDecision.Clarify clarify) {
                eager.cancelAll();
                run.recordAssistant(step, decision, List.of());
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "CLARIFY", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), null));
                return finish(run, AgentTerminalState.NEED_CLARIFICATION, Map.of(), clarify.question(),
                        null, false, "AGENT");
            }
            if (decision instanceof AgentDecision.Fallback fallback) {
                eager.cancelAll();
                run.recordAssistant(step, decision, List.of());
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "FALLBACK", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), fallback.reason()));
                return finishFailure(run, definition, fallback.reason(), null);
            }

            List<AgentDecision.ToolCall> calls = toolCalls(decision);
            if (toolCalls + calls.size() > definition.maxToolCalls()) {
                eager.cancelAll();
                run.recordAssistant(step, decision, List.of());
                return finishFailure(run, definition, "TOOL_CALL_LIMIT_REACHED", null);
            }
            toolCalls += calls.size();
            List<PreparedToolCall> prepared;
            try {
                List<PreparedToolCall> preparedCalls = new ArrayList<>();
                for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                    preparedCalls.add(prepare(
                            calls.get(callIndex), effectiveTools, run.snapshot.toolSchemaVersions(),
                            request, step, callIndex));
                }
                prepared = List.copyOf(preparedCalls);
            } catch (ToolPolicyFailure policyFailure) {
                eager.cancelAll();
                run.recordAssistant(step, decision, List.of());
                return finishFailure(run, definition, policyFailure.code, null);
            } catch (IllegalArgumentException invalidArguments) {
                eager.cancelAll();
                run.recordAssistant(step, decision, List.of());
                return finishFailure(run, definition, "TOOL_ARGUMENT_INVALID", null);
            }
            AgentMessage.Assistant assistant = run.recordAssistant(step, decision, prepared);
            int journalStep = step;
            List<ToolCallJournalEntry> journalEntries = prepared.stream()
                    .map(call -> run.journalEntry(
                            journalStep, call, assistant, ToolJournalStatus.DECIDED, null))
                    .toList();
            try {
                run.recordToolDecision(preTurnCheckpoint, journalEntries);
            } catch (RuntimeException persistenceFailure) {
                eager.cancelAll();
                throw persistenceFailure;
            }
            List<PreparedToolCall> approvalCalls = prepared.stream()
                    .filter(call -> call.approvalReason() != null)
                    .toList();
            if (!approvalCalls.isEmpty()) {
                eager.cancelAll();
                if (prepared.size() != 1 || approvalCalls.size() != 1) {
                    return finishFailure(
                            run, definition, "MULTIPLE_TOOL_APPROVAL_UNSUPPORTED", null);
                }
                PreparedToolCall approval = approvalCalls.getFirst();
                ToolCallJournalEntry waitingCall = journalEntries.getFirst().withStatus(
                        ToolJournalStatus.WAITING, null, clock.instant());
                WaitState.Hitl waitState = approvalWait(run, approval);
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "CALL_TOOL", "WAITING_APPROVAL", approval.toolCallId(),
                        approval.tool().name(), null, elapsedMillis(decisionStarted), null));
                run.record(AgentRunEvent.Type.TOOL_COMPLETED, Map.of(
                        "step", step,
                        "toolCallId", approval.toolCallId(),
                        "toolName", approval.tool().name(),
                        "schemaVersion", approval.tool().schema().version(),
                        "status", "WAITING_APPROVAL"));
                return finishWaiting(run, waitState, waitingCall);
            }
            eager.retainOnly(prepared);
            boolean noProgress = false;
            for (PreparedToolCall call : prepared) {
                String fingerprint = call.tool().name() + ":" + new TreeMap<>(call.arguments());
                if (!completedInvocations.add(fingerprint)) {
                    noProgress = true;
                }
            }
            if (noProgress) {
                eager.cancelAll();
                for (PreparedToolCall call : prepared) {
                    run.steps.add(new AgentRunTrace.StepTrace(
                            step, "CALL_TOOL", "FAILED", call.toolCallId(),
                            call.tool().name(), null, 0, "NO_PROGRESS_DETECTED"));
                    run.record(AgentRunEvent.Type.TOOL_COMPLETED, Map.of(
                            "step", step,
                            "toolCallId", call.toolCallId(),
                            "toolName", call.tool().name(),
                            "schemaVersion", call.tool().schema().version(),
                            "status", "FAILED",
                            "errorCode", "NO_PROGRESS_DETECTED"));
                    run.recordToolResult(
                            step,
                            call,
                            AgentMessage.ToolResultStatus.FAILED,
                            "NO_PROGRESS_DETECTED");
                    AgentToolObservation failed = failedObservation(
                            call, "NO_PROGRESS_DETECTED", 0);
                    run.recordJournalResult(journalEntries.get(call.index()).withStatus(
                            ToolJournalStatus.FAILED, failed, clock.instant()));
                }
                return finishFailure(run, definition, "NO_PROGRESS_DETECTED", null);
            }

            List<AgentToolObservation> completed;
            try {
                completed = executeBatch(
                        run, request, prepared, deadlineNanos, cancellationToken,
                        ToolExecutionObserver.Source.INITIAL, eager);
            } catch (CallFailure failure) {
                if (failure.cancellationCause != null
                        || "AGENT_DEADLINE_EXCEEDED".equals(failure.code)) {
                    AgentMessage.ToolResultStatus messageStatus = failure.cancellationCause == null
                            ? AgentMessage.ToolResultStatus.TIMED_OUT
                            : AgentMessage.ToolResultStatus.CANCELLED;
                    for (PreparedToolCall call : prepared) {
                        AgentToolObservation durable = run.durableResults.get(call.toolCallId());
                        if (durable != null) {
                            run.recordToolResult(step, durable);
                            run.steps.add(new AgentRunTrace.StepTrace(step, "CALL_TOOL",
                                    durable.result().success() ? "SUCCEEDED" : "FAILED",
                                    call.toolCallId(), call.tool().name(), durable.result().linkedTraceId(),
                                    durable.tookMillis(), durable.result().errorCode()));
                            continue;
                        }
                        run.steps.add(new AgentRunTrace.StepTrace(
                                step,
                                "CALL_TOOL",
                                messageStatus.name(),
                                call.toolCallId(),
                                call.tool().name(),
                                null,
                                0,
                                failure.code));
                        run.record(AgentRunEvent.Type.TOOL_COMPLETED, Map.of(
                                "step", step,
                                "toolCallId", call.toolCallId(),
                                "toolName", call.tool().name(),
                                "schemaVersion", call.tool().schema().version(),
                                "status", messageStatus.name(),
                                "errorCode", failure.code));
                        run.recordToolResult(step, call, messageStatus, failure.code);
                        ToolJournalStatus journalStatus = failure.cancellationCause == null
                                ? ToolJournalStatus.TIMED_OUT
                                : ToolJournalStatus.CANCELLED;
                        run.recordJournalResult(journalEntries.get(call.index()).withStatus(
                                journalStatus,
                                failedObservation(call, failure.code, 0),
                                clock.instant()));
                    }
                }
                if (failure.cancellationCause != null) {
                    return finishCancelled(run, failure.cancellationCause);
                }
                return finishFailure(run, definition, failure.code, null);
            }
            CancellationCause afterTools = cancellationToken.cause();
            if (afterTools != null) {
                for (PreparedToolCall call : prepared) {
                    AgentToolObservation durable = run.durableResults.get(call.toolCallId());
                    if (durable != null) {
                        run.recordToolResult(step, durable);
                        run.steps.add(new AgentRunTrace.StepTrace(step, "CALL_TOOL",
                                durable.result().success() ? "SUCCEEDED" : "FAILED",
                                call.toolCallId(), call.tool().name(), durable.result().linkedTraceId(),
                                durable.tookMillis(), durable.result().errorCode()));
                        continue;
                    }
                    run.steps.add(new AgentRunTrace.StepTrace(
                            step, "CALL_TOOL", "CANCELLED", call.toolCallId(),
                            call.tool().name(), null, 0, afterTools.name()));
                    run.record(AgentRunEvent.Type.TOOL_COMPLETED, Map.of(
                            "step", step,
                            "toolCallId", call.toolCallId(),
                            "toolName", call.tool().name(),
                            "schemaVersion", call.tool().schema().version(),
                            "status", "CANCELLED",
                            "errorCode", afterTools.name()));
                    run.recordToolResult(
                            step,
                            call,
                            AgentMessage.ToolResultStatus.CANCELLED,
                            afterTools.name());
                    run.recordJournalResult(journalEntries.get(call.index()).withStatus(
                            ToolJournalStatus.CANCELLED,
                            failedObservation(call, afterTools.name(), 0),
                            clock.instant()));
                }
                return finishCancelled(run, afterTools);
            }
            List<AgentToolObservation> waitingObservations = completed.stream()
                    .filter(observation -> observation.result().waiting())
                    .toList();
            if (!waitingObservations.isEmpty()) {
                if (completed.size() != 1 || waitingObservations.size() != 1) {
                    return finishFailure(
                            run, definition, "MULTIPLE_TOOL_WAITS_UNSUPPORTED", null);
                }
                AgentToolObservation waiting = waitingObservations.getFirst();
                PreparedToolCall waitingPrepared = prepared.getFirst();
                if (waitingPrepared.tool().effect() == AgentTool.Effect.MUTATING) {
                    return finishFailure(
                            run, definition, "MUTATING_TOOL_WAIT_UNSUPPORTED", null);
                }
                WaitState waitState = toolWait(run, waitingPrepared, waiting.result().waitRequest());
                ToolCallJournalEntry waitingCall = journalEntries.getFirst().forAttempt(
                        run.runId, ToolJournalStatus.WAITING, null, clock.instant());
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "CALL_TOOL", "WAITING_" + waitState.type().name(),
                        waiting.toolCallId(), waiting.toolName(), null,
                        waiting.tookMillis(), null));
                run.record(AgentRunEvent.Type.TOOL_COMPLETED, Map.of(
                        "step", step,
                        "toolCallId", waiting.toolCallId(),
                        "toolName", waiting.toolName(),
                        "schemaVersion", waiting.schemaVersion(),
                        "status", "WAITING_" + waitState.type().name()));
                return finishWaiting(run, waitState, waitingCall);
            }
            observations.addAll(completed);
            for (AgentToolObservation observation : completed) {
                AgentToolResult toolResult = observation.result();
                run.steps.add(new AgentRunTrace.StepTrace(
                        step,
                        "CALL_TOOL",
                        toolResult.success()
                                ? observation.argumentsRepaired() ? "SUCCEEDED_REPAIRED" : "SUCCEEDED"
                                : "FAILED",
                        observation.toolCallId(),
                        observation.toolName(),
                        toolResult.linkedTraceId(),
                        observation.tookMillis(),
                        toolResult.errorCode()));
                run.record(AgentRunEvent.Type.TOOL_COMPLETED, toolPayload(step, observation));
                run.recordToolResult(step, observation);
                ToolJournalStatus journalStatus = toolResult.success()
                        ? ToolJournalStatus.SUCCEEDED
                        : ToolJournalStatus.FAILED;
                int callIndex = indexOfCall(prepared, observation.toolCallId());
                run.recordJournalResult(journalEntries.get(callIndex).withStatus(
                        journalStatus, observation, clock.instant()));
            }
            List<AgentToolObservation> switches = completed.stream()
                    .filter(observation -> observation.result().success())
                    .filter(observation -> observation.result().toolGroupSwitch() != null)
                    .toList();
            if (switches.size() > 1) {
                return finishFailure(
                        run, definition, "MULTIPLE_TOOL_GROUP_SWITCH_UNSUPPORTED", null);
            }
            if (!switches.isEmpty()) {
                try {
                    run.switchToolGroups(switches.getFirst().result()
                            .toolGroupSwitch().activeGroups());
                } catch (IllegalArgumentException invalidSwitch) {
                    return finishFailure(run, definition, "TOOL_GROUP_SWITCH_INVALID", null);
                }
                if (tools.containsMutating(
                        run.capabilities().effectiveTools(), run.snapshot.toolSchemaVersions())
                        && !recovery.sideEffectLedgerEnabled()) {
                    return finishFailure(
                            run, definition, "MUTATING_TOOL_LEDGER_REQUIRED", null);
                }
            }
            if (completed.stream().noneMatch(observation -> observation.result().success())) {
                AgentToolResult first = completed.getFirst().result();
                return finishFailure(run, definition, first.errorCode(), first.linkedTraceId());
            }
            run.savePostTurn(
                    step + 1,
                    toolCalls,
                    observations,
                    completedInvocations);
        }
        return finishFailure(run, definition, "STEP_LIMIT_REACHED", null);
    }

    private AgentRunResult finishFailure(
            RunState run,
            AgentDefinition definition,
            String reason,
            String linkedTraceId) {
        if (linkedTraceId != null) {
            run.steps.add(new AgentRunTrace.StepTrace(
                    run.steps.size() + 1,
                    "FALLBACK",
                    "REQUIRED",
                    null,
                    null,
                    linkedTraceId,
                    0,
                    reason));
        }
        AgentTerminalState state = definition.fallbackEnabled()
                ? AgentTerminalState.FALLBACK_REQUIRED
                : AgentTerminalState.FAILED;
        return finish(run, state, Map.of(), null, reason, true,
                definition.fallbackEnabled() ? "FALLBACK_REQUIRED" : "FAILED");
    }

    private AgentRunResult finishCancelled(RunState run, CancellationCause cause) {
        return finish(
                run,
                AgentTerminalState.CANCELLED,
                Map.of(),
                null,
                null,
                cause.name(),
                false,
                "AGENT");
    }

    private AgentRunResult finishWaiting(
            RunState run,
            WaitState waitState,
            ToolCallJournalEntry waitingCall) {
        return finish(
                run,
                AgentTerminalState.WAITING,
                Map.of("waitId", waitState.waitId(), "waitType", waitState.type().name()),
                waitState instanceof WaitState.Hitl hitl ? hitl.reason() : null,
                null,
                null,
                false,
                "AGENT_WAITING",
                waitState,
                waitingCall);
    }

    private AgentRunResult finish(
            RunState run,
            AgentTerminalState state,
            Map<String, Object> output,
            String clarification,
            String fallbackReason,
            boolean degraded,
            String executionMode) {
        return finish(run, state, output, clarification, fallbackReason, null, degraded,
                executionMode, null, null);
    }

    private AgentRunResult finish(
            RunState run,
            AgentTerminalState state,
            Map<String, Object> output,
            String clarification,
            String fallbackReason,
            String cancellationReason,
            boolean degraded,
            String executionMode) {
        return finish(run, state, output, clarification, fallbackReason, cancellationReason,
                degraded, executionMode, null, null);
    }

    private AgentRunResult finish(
            RunState run,
            AgentTerminalState state,
            Map<String, Object> output,
            String clarification,
            String fallbackReason,
            String cancellationReason,
            boolean degraded,
            String executionMode,
            WaitState waitState,
            ToolCallJournalEntry waitingCall) {
        AgentRunTrace trace = new AgentRunTrace(
                run.runId,
                run.request.requestId(),
                run.request.sessionId(),
                run.request.turnId(),
                run.snapshot,
                run.startedAt,
                elapsedMillis(run.startedNanos),
                state,
                executionMode,
                fallbackReason,
                cancellationReason,
                run.llmUsage,
                run.steps);
        Map<String, Object> payload = new HashMap<>();
        payload.put("state", state.name());
        payload.put("executionMode", executionMode);
        payload.put("degraded", degraded);
        payload.put("messageCount", run.messages.size());
        payload.put("trace", trace);
        if (fallbackReason != null) {
            payload.put("fallbackReason", fallbackReason);
        }
        if (cancellationReason != null) {
            payload.put("cancellationReason", cancellationReason);
        }
        if (waitState != null) {
            payload.put("waitId", waitState.waitId());
            payload.put("waitType", waitState.type().name());
        }
        run.record(AgentRunEvent.Type.RUN_COMPLETED, payload);
        AgentRunResult result = new AgentRunResult(
                state, output, clarification, fallbackReason, cancellationReason, degraded,
                run.messages, waitState, trace);
        if (waitState == null) {
            run.saveTerminal(result);
        } else {
            run.saveWait(result, waitState, waitingCall);
        }
        return result;
    }

    private WaitState.Hitl approvalWait(RunState run, PreparedToolCall call) {
        Instant createdAt = clock.instant();
        Instant deadlineAt = createdAt.plus(call.approvalTimeout());
        String waitId = UUID.nameUUIDFromBytes(("wait:hitl:" + run.request.sessionId()
                + ":" + call.toolCallId()).getBytes(StandardCharsets.UTF_8)).toString();
        return new WaitState.Hitl(
                1,
                waitId,
                run.request.sessionId(),
                run.request.requestId(),
                run.request.turnId(),
                run.checkpointId(CheckpointBoundary.SUSPENDED, call.step()),
                call.toolCallId(),
                createdAt,
                deadlineAt,
                call.approvalReason(),
                call.tool().name(),
                call.arguments());
    }

    private WaitState toolWait(
            RunState run, PreparedToolCall call, WaitRequest request) {
        Instant createdAt = clock.instant();
        long remainingMillis = Math.max(
                1, TimeUnit.NANOSECONDS.toMillis(remainingNanos(run.deadlineNanos)));
        Instant deadlineAt = createdAt.plus(request.timeout());
        String waitId = UUID.nameUUIDFromBytes(("wait:" + request.getClass().getSimpleName()
                + ":" + run.request.sessionId() + ":" + call.toolCallId())
                .getBytes(StandardCharsets.UTF_8)).toString();
        String checkpointId = run.checkpointId(CheckpointBoundary.SUSPENDED, call.step());
        return switch (request) {
            case WaitRequest.AsyncTask async -> new WaitState.AsyncTask(
                    1, waitId, run.request.sessionId(), run.request.requestId(),
                    run.request.turnId(), checkpointId, call.toolCallId(), createdAt,
                    deadlineAt, async.taskId(), async.callbackType());
            case WaitRequest.Waitpoint waitpoint -> new WaitState.Waitpoint(
                    1, waitId, run.request.sessionId(), run.request.requestId(),
                    run.request.turnId(), checkpointId, call.toolCallId(), createdAt,
                    deadlineAt, waitpoint.key(), waitpoint.condition());
            case WaitRequest.Handoff handoff -> new WaitState.Handoff(
                    1, waitId, run.request.sessionId(), run.request.requestId(),
                    run.request.turnId(), checkpointId, call.toolCallId(), createdAt,
                    deadlineAt, handoff.targetAgentId(), handoff.targetSessionId());
            case WaitRequest.ChildAgent child -> new WaitState.ChildAgent(
                    1, waitId, run.request.sessionId(), run.request.requestId(),
                    run.request.turnId(), checkpointId, call.toolCallId(), createdAt,
                    deadlineAt, child.childAgentId(), child.childSessionId(), child.depth(),
                    Math.min(child.remainingBudgetMillis(), remainingMillis));
        };
    }

    private static Map<String, Object> decisionPayload(int step, AgentDecision decision) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("step", step);
        payload.put("decisionType", decision.getClass().getSimpleName());
        if (decision instanceof AgentDecision.CallTool call) {
            payload.put("toolName", call.toolName());
            payload.put("arguments", call.arguments());
        } else if (decision instanceof AgentDecision.CallTools calls) {
            payload.put("tools", calls.calls());
        }
        return payload;
    }

    private static Map<String, Object> toolPayload(int step, AgentToolObservation observation) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("step", step);
        payload.put("toolCallId", observation.toolCallId());
        payload.put("toolName", observation.toolName());
        payload.put("schemaVersion", observation.schemaVersion());
        payload.put("argumentsRepaired", observation.argumentsRepaired());
        payload.put("status", observation.result().success() ? "SUCCEEDED" : "FAILED");
        payload.put("tookMillis", observation.tookMillis());
        if (observation.result().errorCode() != null) {
            payload.put("errorCode", observation.result().errorCode());
        }
        if (observation.result().linkedTraceId() != null) {
            payload.put("linkedTraceId", observation.result().linkedTraceId());
        }
        return payload;
    }

    private ResumeBatch resumePendingTools(
            RunState run,
            List<ToolCallJournalEntry> journalEntries,
            List<AgentToolObservation> observations,
            Set<String> completedInvocations,
            long deadlineNanos,
            CancellationToken cancellationToken) {
        if (journalEntries.isEmpty()) {
            throw new IllegalStateException("pending Tool recovery requires journal entries");
        }
        int step = journalEntries.getFirst().step();
        journalEntries.forEach(entry -> run.decisions.put(entry.toolCallId(), entry));
        if (journalEntries.stream().anyMatch(entry -> entry.step() != step)) {
            throw new IllegalStateException("a recovery batch cannot span multiple steps");
        }
        AgentMessage.Assistant assistant = journalEntries.getFirst().assistantMessage();
        if (journalEntries.stream().anyMatch(entry ->
                !entry.assistantMessage().messageId().equals(assistant.messageId()))) {
            throw new IllegalStateException("a recovery batch must reference one assistant message");
        }
        if (run.messages.stream().noneMatch(message -> message.messageId().equals(assistant.messageId()))) {
            run.messages.add(assistant);
        }

        List<PreparedToolCall> pending = new ArrayList<>();
        Map<String, AgentToolObservation> reconciled = new HashMap<>();
        boolean anySucceeded = false;
        String firstFailure = null;
        CancellationCause recoveredCancellation = null;
        boolean recoveredTimeout = false;
        for (ToolCallJournalEntry entry : journalEntries) {
            String fingerprint = entry.toolName() + ":" + new TreeMap<>(entry.arguments());
            if (!completedInvocations.add(fingerprint)) {
                return new ResumeBatch(
                        step + 1,
                        Math.max(run.latestToolCallCount, 0) + journalEntries.size(),
                        "NO_PROGRESS_DETECTED",
                        null);
            }
            if (!entry.status().terminal()) {
                PreparedToolCall call = restorePrepared(entry);
                if (entry.effect() == AgentTool.Effect.MUTATING) {
                    AgentToolObservation recovered = recoverMutatingSideEffect(
                            run, entry, call, deadlineNanos, cancellationToken);
                    if (recovered != null) {
                        reconciled.put(entry.toolCallId(), recovered);
                    } else {
                        pending.add(call);
                    }
                } else {
                    pending.add(call);
                }
            }
        }

        Map<String, AgentToolObservation> newlyCompleted = new HashMap<>(reconciled);
        CallFailure batchFailure = null;
        if (!pending.isEmpty()) {
            try {
                for (AgentToolObservation observation : executeBatch(
                        run,
                        run.request,
                        pending,
                        deadlineNanos,
                        cancellationToken,
                        ToolExecutionObserver.Source.RECOVERY,
                        null)) {
                    newlyCompleted.put(observation.toolCallId(), observation);
                }
            } catch (CallFailure failure) {
                newlyCompleted.putAll(run.durableResults);
                batchFailure = failure;
            }
        }

        for (ToolCallJournalEntry entry : journalEntries) {
            AgentToolObservation observation = entry.observation();
            ToolJournalStatus journalStatus = entry.status();
            if (!entry.status().terminal()) {
                observation = newlyCompleted.get(entry.toolCallId());
                if (observation == null) {
                    String errorCode = batchFailure == null
                            ? "TOOL_RECOVERY_RESULT_MISSING" : batchFailure.code;
                    observation = failedObservation(restorePrepared(entry), errorCode, 0);
                    journalStatus = batchFailure != null && batchFailure.cancellationCause != null
                            ? ToolJournalStatus.CANCELLED
                            : "AGENT_DEADLINE_EXCEEDED".equals(errorCode)
                                    ? ToolJournalStatus.TIMED_OUT
                                    : ToolJournalStatus.FAILED;
                } else {
                    journalStatus = recoveredJournalStatus(observation.result());
                }
                run.recordJournalResult(entry.forAttempt(
                        run.runId, journalStatus, observation, clock.instant()));
            }
            observations.add(observation);
            AgentMessage.ToolResultStatus messageStatus = messageStatus(journalStatus);
            anySucceeded |= journalStatus == ToolJournalStatus.SUCCEEDED;
            if (firstFailure == null && observation.result().errorCode() != null) {
                firstFailure = observation.result().errorCode();
            }
            if (journalStatus == ToolJournalStatus.CANCELLED) {
                recoveredCancellation = cancellationCause(observation.result().errorCode());
            }
            recoveredTimeout |= journalStatus == ToolJournalStatus.TIMED_OUT;
            if (run.messages.stream().noneMatch(message ->
                    message instanceof AgentMessage.ToolResult result
                            && result.toolCallId().equals(entry.toolCallId()))) {
                run.messages.add(run.toolResultMessage(
                        entry.status().terminal() ? entry.attemptId() : run.runId,
                        step,
                        entry.toolCallId(),
                        entry.toolName(),
                        entry.toolSchemaVersion(),
                        messageStatus,
                        observation.result().output(),
                        observation.result().errorCode(),
                        observation.result().linkedTraceId(),
                        entry.argumentsRepaired(),
                        observation.tookMillis()));
            }
            run.steps.add(new AgentRunTrace.StepTrace(
                    step,
                    "CALL_TOOL",
                    entry.status().terminal()
                            ? "RECOVERED_" + journalStatus.name()
                            : journalStatus.name(),
                    entry.toolCallId(),
                    entry.toolName(),
                    observation.result().linkedTraceId(),
                    observation.tookMillis(),
                    observation.result().errorCode()));
            run.record(AgentRunEvent.Type.TOOL_COMPLETED, toolPayload(step, observation));
        }
        String recoveredFailure = null;
        List<AgentToolObservation> recoveredSwitches = journalEntries.stream()
                .map(entry -> entry.status().terminal()
                        ? entry.observation() : newlyCompleted.get(entry.toolCallId()))
                .filter(Objects::nonNull)
                .filter(observation -> observation.result().success())
                .filter(observation -> observation.result().toolGroupSwitch() != null)
                .toList();
        if (recoveredSwitches.size() > 1) {
            recoveredFailure = "MULTIPLE_TOOL_GROUP_SWITCH_UNSUPPORTED";
        } else if (!recoveredSwitches.isEmpty()) {
            try {
                run.switchToolGroups(recoveredSwitches.getFirst().result()
                        .toolGroupSwitch().activeGroups());
            } catch (IllegalArgumentException invalidSwitch) {
                recoveredFailure = "TOOL_GROUP_SWITCH_INVALID";
            }
        }
        if (recoveredFailure == null
                && batchFailure != null && batchFailure.cancellationCause == null) {
            recoveredFailure = batchFailure.code;
        } else if (recoveredFailure == null && recoveredTimeout) {
            recoveredFailure = firstFailure == null
                    ? "AGENT_DEADLINE_EXCEEDED" : firstFailure;
        } else if (recoveredFailure == null && !anySucceeded && recoveredCancellation == null) {
            recoveredFailure = firstFailure == null ? "TOOL_RECOVERY_FAILED" : firstFailure;
        }
        CancellationCause cancellation = batchFailure != null
                ? batchFailure.cancellationCause
                : recoveredCancellation;
        return new ResumeBatch(
                step + 1,
                Math.max(run.latestToolCallCount, 0) + journalEntries.size(),
                recoveredFailure,
                cancellation);
    }

    private PreparedToolCall restorePrepared(ToolCallJournalEntry entry) {
        AgentTool tool = tools.require(entry.toolName(), entry.toolSchemaVersion());
        if (tool.effect() != entry.effect()) {
            throw new IllegalStateException("checkpoint Tool effect no longer matches");
        }
        tool.schema().validate(entry.arguments());
        if (!argumentsDigest(entry.arguments()).equals(entry.argumentsDigest())) {
            throw new IllegalStateException("checkpoint Tool arguments digest does not match");
        }
        return new PreparedToolCall(
                entry.toolCallId(),
                tool,
                entry.arguments(),
                entry.argumentsRepaired(),
                entry.step(),
                entry.callIndex(),
                null,
                null);
    }

    private static AgentMessage.ToolResultStatus messageStatus(ToolJournalStatus status) {
        return switch (status) {
            case SUCCEEDED -> AgentMessage.ToolResultStatus.SUCCEEDED;
            case FAILED -> AgentMessage.ToolResultStatus.FAILED;
            case CANCELLED -> AgentMessage.ToolResultStatus.CANCELLED;
            case TIMED_OUT -> AgentMessage.ToolResultStatus.TIMED_OUT;
            case DECIDED, EXECUTING, UNKNOWN, WAITING -> AgentMessage.ToolResultStatus.WAITING;
        };
    }

    private static CancellationCause cancellationCause(String value) {
        try {
            return CancellationCause.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException unknown) {
            return CancellationCause.SHUTDOWN;
        }
    }

    private static AgentToolObservation failedObservation(
            PreparedToolCall call,
            String errorCode,
            long tookMillis) {
        return new AgentToolObservation(
                call.toolCallId(),
                call.tool().name(),
                call.tool().schema().version(),
                call.arguments(),
                call.argumentsRepaired(),
                AgentToolResult.failure(errorCode),
                tookMillis);
    }

    private static int indexOfCall(List<PreparedToolCall> calls, String toolCallId) {
        for (int index = 0; index < calls.size(); index++) {
            if (calls.get(index).toolCallId().equals(toolCallId)) {
                return index;
            }
        }
        throw new IllegalStateException("completed Tool did not belong to the prepared batch");
    }

    private static void validateCheckpoint(
            RuntimeCheckpoint checkpoint,
            AgentRunRequest request,
            AgentDefinition definition) {
        if (!checkpoint.sessionId().equals(request.sessionId())
                || !checkpoint.requestId().equals(request.requestId())
                || !checkpoint.turnId().equals(request.turnId())) {
            throw new IllegalStateException("checkpoint identity does not match the execution request");
        }
        AgentRunTrace.DefinitionSnapshot frozen = checkpoint.definition();
        if (!frozen.id().equals(definition.id())
                || !frozen.version().equals(definition.version())
                || !frozen.plannerVersion().equals(definition.plannerVersion())
                || !frozen.promptVersion().equals(definition.promptVersion())
                || !frozen.decisionProviderVersion().equals(definition.decisionProviderVersion())
                || frozen.maxSteps() != definition.maxSteps()
                || frozen.maxToolCalls() != definition.maxToolCalls()
                || frozen.timeoutMillis() != definition.timeout().toMillis()) {
            throw new IllegalStateException("checkpoint frozen definition no longer matches");
        }
    }

    private static String argumentsDigest(Map<String, Object> arguments) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    canonicalValue(arguments).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String canonicalValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey((left, right) ->
                            String.valueOf(left).compareTo(String.valueOf(right))))
                    .map(entry -> String.valueOf(entry.getKey()) + "="
                            + canonicalValue(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(AgentRuntime::canonicalValue)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
        return String.valueOf(value);
    }

    private AgentToolObservation recoverMutatingSideEffect(
            RunState run,
            ToolCallJournalEntry journal,
            PreparedToolCall call,
            long deadlineNanos,
            CancellationToken cancellationToken) {
        SideEffectLedgerEntry ledger = run.recovery.findSideEffect(call.toolCallId()).orElse(null);
        if (ledger == null) {
            if (journal.status() == ToolJournalStatus.DECIDED) {
                return null;
            }
            throw new UnsafeToolRecoveryException(run.request.sessionId(), call.toolCallId());
        }
        if (!ledger.requestDigest().equals(argumentsDigest(call.arguments()))) {
            throw new IllegalStateException("side-effect ledger request digest does not match");
        }
        if (ledger.status() == SideEffectStatus.PREPARED) {
            return null;
        }
        if (ledger.status().terminal()) {
            return observationFromLedger(call, ledger, 0);
        }
        if (ledger.status() != SideEffectStatus.UNKNOWN
                && ledger.status() != SideEffectStatus.EXECUTING) {
            throw new UnsafeToolRecoveryException(run.request.sessionId(), call.toolCallId());
        }
        if (!(call.tool() instanceof AgentToolReconciler reconciler)) {
            throw new UnsafeToolRecoveryException(run.request.sessionId(), call.toolCallId());
        }

        long started = System.nanoTime();
        observe(call, run.runId, ToolExecutionObserver.Source.RECONCILIATION,
                ToolExecutionObserver.Phase.BEFORE, 0, "STARTED");
        SideEffectReconciliation reconciliation;
        try {
            CancellationToken toolToken = cancellationToken.child();
            AgentToolContext context = new AgentToolContext(
                    run.runId,
                    call.toolCallId(),
                    ledger.idempotencyKey(),
                    run.request,
                    call.arguments(),
                    Duration.ofNanos(remainingNanos(deadlineNanos)),
                    toolToken);
            reconciliation = invoke(
                    () -> callGuard.execute(
                            AgentCallGuard.CallType.TOOL,
                            () -> reconciler.reconcile(ledger, context)),
                    deadlineNanos,
                    cancellationToken);
        } catch (CallFailure failure) {
            observe(call, run.runId, ToolExecutionObserver.Source.RECONCILIATION,
                    ToolExecutionObserver.Phase.FAILURE, elapsedMillis(started), failure.code);
            if (failure.cancellationCause != null
                    || "AGENT_DEADLINE_EXCEEDED".equals(failure.code)) {
                return failedObservation(call, failure.code, elapsedMillis(started));
            }
            throw new UnsafeToolRecoveryException(run.request.sessionId(), call.toolCallId());
        }
        if (reconciliation == null
                || reconciliation.resolution() == SideEffectReconciliation.Resolution.UNKNOWN) {
            observe(call, run.runId, ToolExecutionObserver.Source.RECONCILIATION,
                    ToolExecutionObserver.Phase.FAILURE, elapsedMillis(started), "STILL_UNKNOWN");
            throw new UnsafeToolRecoveryException(run.request.sessionId(), call.toolCallId());
        }
        AgentToolResult result = reconciliation.result();
        SideEffectLedgerEntry resolved = ledger.forAttempt(
                run.runId,
                SideEffectStatus.RECONCILED,
                resultDigest(result),
                result,
                reconciliation.method(),
                reconciliation.note(),
                clock.instant());
        run.recovery.recordSideEffectResult(resolved);
        long tookMillis = elapsedMillis(started);
        observe(call, run.runId, ToolExecutionObserver.Source.RECONCILIATION,
                result.success() ? ToolExecutionObserver.Phase.AFTER : ToolExecutionObserver.Phase.FAILURE,
                tookMillis,
                result.success() ? "RECONCILED_SUCCEEDED" : result.errorCode());
        return observationFromLedger(call, resolved, tookMillis);
    }

    private static AgentToolObservation observationFromLedger(
            PreparedToolCall call,
            SideEffectLedgerEntry ledger,
            long tookMillis) {
        return new AgentToolObservation(
                call.toolCallId(), call.tool().name(), call.tool().schema().version(),
                call.arguments(), call.argumentsRepaired(), ledger.result(), tookMillis);
    }

    private List<AgentToolObservation> executeBatch(
            RunState run,
            AgentRunRequest request,
            List<PreparedToolCall> calls,
            long deadlineNanos,
            CancellationToken cancellationToken,
            ToolExecutionObserver.Source source,
            EagerToolBatch eager) throws CallFailure {
        CancellationToken batchToken = cancellationToken.child();
        List<PendingToolCall> pending = new ArrayList<>();
        for (PreparedToolCall call : calls) {
            CancellationCause cause = batchToken.cause();
            if (cause != null) {
                abandonPending(run, request, pending);
                throw CallFailure.cancelled(cause, null);
            }
            long started = System.nanoTime();
            SideEffectLedgerEntry ledger = null;
            if (remainingNanos(deadlineNanos) <= 0) {
                pending.add(new PendingToolCall(
                        call, started, null, "AGENT_DEADLINE_EXCEEDED", null, source));
                continue;
            }
            try {
                PendingToolCall eagerCall = eager == null ? null : eager.take(call);
                if (eagerCall != null) {
                    run.recovery.markToolExecuting(
                            request.sessionId(), request.requestId(), run.runId,
                            List.of(call.toolCallId()));
                    pending.add(eagerCall);
                    continue;
                }
                if (call.tool().effect() == AgentTool.Effect.MUTATING) {
                    ledger = prepareSideEffect(run, call);
                }
                run.recovery.markToolExecuting(
                        request.sessionId(), request.requestId(), run.runId,
                        List.of(call.toolCallId()));
                if (ledger != null) {
                    ledger = run.recovery.markSideEffectExecuting(ledger, run.runId);
                }
                CancellationToken toolToken = batchToken.child();
                SideEffectLedgerEntry executingLedger = ledger;
                run.publish(new PushEvent.ToolStarted(
                        run.runId, clock.instant(), call.toolCallId(), call.tool().name(),
                        call.index(), false));
                observe(call, run.runId, source, ToolExecutionObserver.Phase.BEFORE, 0, "STARTED");
                Future<AgentToolInvocation> future = executor.submit(() -> {
                    AgentToolContext context = new AgentToolContext(
                            run.runId,
                            call.toolCallId(),
                            executingLedger == null
                                    ? "tool-call:" + call.toolCallId()
                                    : executingLedger.idempotencyKey(),
                            request,
                            call.arguments(),
                            Duration.ofNanos(remainingNanos(deadlineNanos)),
                            toolToken);
                    return callGuard.execute(
                            AgentCallGuard.CallType.TOOL,
                            () -> toolExecutor.execute(
                                    call.tool().name(), call.tool().schema().version(),
                                    call.arguments(), context));
                });
                pending.add(new PendingToolCall(call, started, future, null, ledger, source));
            } catch (RejectedExecutionException rejected) {
                pending.add(new PendingToolCall(
                        call, started, null, "RUNTIME_SATURATED", ledger, source));
            }
        }

        List<AgentToolObservation> observations = new ArrayList<>();
        for (PendingToolCall item : pending) {
            AgentToolResult result;
            boolean sideEffectUnknown = false;
            if (item.immediateError() != null) {
                result = AgentToolResult.failure(item.immediateError());
            } else {
                long remaining = remainingNanos(deadlineNanos);
                if (remaining <= 0) {
                    abandonPending(run, request, pending);
                    throw CallFailure.failed("AGENT_DEADLINE_EXCEEDED", null);
                } else {
                    try {
                        result = await(item.future(), deadlineNanos, batchToken).result();
                    } catch (CallFailure failure) {
                        if (failure.cancellationCause != null
                                || "AGENT_DEADLINE_EXCEEDED".equals(failure.code)) {
                            abandonPending(run, request, pending);
                            throw failure;
                        }
                        if (item.ledger() != null) {
                            run.recovery.markSideEffectsUnknown(
                                    request.sessionId(), request.requestId(),
                                    List.of(item.call().toolCallId()));
                            result = AgentToolResult.failure("MUTATING_TOOL_STATE_UNKNOWN");
                            sideEffectUnknown = true;
                        } else {
                            result = AgentToolResult.failure(failure.code);
                        }
                    }
                }
            }
            PreparedToolCall call = item.call();
            long tookMillis = elapsedMillis(item.startedNanos());
            if (item.ledger() != null && !sideEffectUnknown) {
                run.recovery.afterMutatingToolReturn();
                SideEffectStatus ledgerStatus = result.success()
                        ? SideEffectStatus.SUCCEEDED : SideEffectStatus.FAILED;
                SideEffectLedgerEntry terminal = item.ledger().forAttempt(
                        run.runId, ledgerStatus, resultDigest(result), result,
                        null, null, clock.instant());
                run.recovery.recordSideEffectResult(terminal);
            }
            observe(call, run.runId, item.source(),
                    result.success() ? ToolExecutionObserver.Phase.AFTER : ToolExecutionObserver.Phase.FAILURE,
                    tookMillis,
                    result.success() ? "SUCCEEDED" : result.errorCode());
            AgentToolObservation observation = new AgentToolObservation(
                    call.toolCallId(),
                    call.tool().name(),
                    call.tool().schema().version(),
                    call.arguments(),
                    call.argumentsRepaired(),
                    result,
                    tookMillis);
            if (!result.waiting()) {
                ToolCallJournalEntry decision = run.decisions.get(call.toolCallId());
                if (decision != null) {
                    run.recordJournalResult(decision.forAttempt(run.runId,
                            recoveredJournalStatus(result), observation, clock.instant()));
                }
            }
            observations.add(observation);
        }
        return List.copyOf(observations);
    }

    private SideEffectLedgerEntry prepareSideEffect(RunState run, PreparedToolCall call) {
        if (!run.recovery.sideEffectLedgerEnabled()) {
            throw new IllegalStateException("MUTATING Tool requires a configured side-effect ledger");
        }
        Instant now = clock.instant();
        SideEffectLedgerEntry entry = new SideEffectLedgerEntry(
                1,
                UUID.nameUUIDFromBytes(("side-effect:" + call.toolCallId())
                        .getBytes(StandardCharsets.UTF_8)).toString(),
                run.request.sessionId(),
                run.request.requestId(),
                run.request.turnId(),
                run.runId,
                call.step(),
                call.index(),
                call.toolCallId(),
                call.tool().name(),
                call.tool().schema().version(),
                "tool-call:" + call.toolCallId(),
                SideEffectStatus.PREPARED,
                argumentsDigest(call.arguments()),
                null,
                null,
                null,
                null,
                now,
                now);
        return run.recovery.prepareSideEffect(entry);
    }

    private void observe(
            PreparedToolCall call,
            String attemptId,
            ToolExecutionObserver.Source source,
            ToolExecutionObserver.Phase phase,
            long durationMillis,
            String outcome) {
        try {
            toolObserver.observe(new ToolExecutionObserver.Event(
                    phase, source, call.toolCallId(), call.tool().name(), call.tool().effect(),
                    attemptId, durationMillis, outcome));
        } catch (RuntimeException ignored) {
            // Observation must never change Tool execution or recovery semantics.
        }
    }

    private static void cancelPending(List<PendingToolCall> pending) {
        for (PendingToolCall item : pending) {
            if (item.future() != null) {
                item.future().cancel(true);
            }
        }
    }

    private static void abandonPending(
            RunState run,
            AgentRunRequest request,
            List<PendingToolCall> pending) {
        cancelPending(pending);
        List<String> mutating = pending.stream()
                .filter(item -> item.ledger() != null)
                .map(item -> item.call().toolCallId())
                .toList();
        run.recovery.markSideEffectsUnknown(
                request.sessionId(), request.requestId(), mutating);
    }

    private PreparedToolCall prepare(
            AgentDecision.ToolCall call,
            Set<String> effectiveTools,
            Map<String, String> frozenSchemaVersions,
            AgentRunRequest request,
            int step,
            int callIndex) {
        if (!effectiveTools.contains(call.toolName())) {
            throw new IllegalArgumentException("tool is not exposed for this request");
        }
        AgentTool tool = tools.require(
                call.toolName(), frozenSchemaVersions.get(call.toolName()));
        Map<String, Object> arguments = call.arguments();
        boolean repaired = false;
        try {
            tool.schema().validate(arguments);
        } catch (IllegalArgumentException invalid) {
            arguments = tool.schema().repair(arguments);
            tool.schema().validate(arguments);
            repaired = true;
        }
        ToolExecutionPolicy.Decision policyDecision;
        try {
            policyDecision = toolPolicy.evaluate(new ToolExecutionPolicy.Context(
                    request, tool.name(), tool.schema().version(), tool.effect(),
                    step, callIndex, arguments));
        } catch (RuntimeException policyError) {
            throw new ToolPolicyFailure("TOOL_POLICY_FAILED");
        }
        if (policyDecision == null) {
            throw new ToolPolicyFailure("TOOL_POLICY_INVALID");
        }
        if (policyDecision.action() == ToolExecutionPolicy.Action.DENY) {
            throw new ToolPolicyFailure("TOOL_POLICY_DENIED");
        }
        String approvalReason = policyDecision.action() == ToolExecutionPolicy.Action.NEED_APPROVAL
                ? policyDecision.reason() == null || policyDecision.reason().isBlank()
                        ? "Tool execution requires approval"
                        : policyDecision.reason()
                : tool.approvalRequired()
                        ? tool.approvalReason()
                        : null;
        if (policyDecision.action() == ToolExecutionPolicy.Action.MODIFY) {
            arguments = policyDecision.arguments();
            tool.schema().validate(arguments);
            repaired = true;
        }
        String identity = request.requestId() + ":" + step + ":" + callIndex + ":"
                + tool.name() + ":" + new TreeMap<>(arguments);
        String toolCallId = UUID.nameUUIDFromBytes(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new PreparedToolCall(
                toolCallId,
                tool,
                arguments,
                repaired,
                step,
                callIndex,
                approvalReason,
                policyDecision.approvalTimeout());
    }

    private static List<AgentDecision.ToolCall> toolCalls(AgentDecision decision) {
        if (decision instanceof AgentDecision.CallTool call) {
            return List.of(new AgentDecision.ToolCall(call.toolName(), call.arguments()));
        }
        return ((AgentDecision.CallTools) decision).calls();
    }

    private static CapabilitySnapshot legacyCapabilities(
            AgentDefinition definition, AgentRunRequest request) {
        Object configured = request.attributes().get("allowedTools");
        if (!(configured instanceof List<?> values)) {
            return CapabilitySnapshot.legacy(definition.allowedTools());
        }
        Set<String> requested = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (requested.isEmpty() || !definition.allowedTools().containsAll(requested)) {
            throw new IllegalArgumentException("request contains an invalid dynamic tool set");
        }
        return CapabilitySnapshot.legacy(requested);
    }

    private AgentRunTrace.DefinitionSnapshot definitionSnapshot(
            AgentDefinition definition, CapabilitySnapshot capabilities) {
        return new AgentRunTrace.DefinitionSnapshot(
                definition.id(),
                definition.version(),
                definition.plannerVersion(),
                definition.promptVersion(),
                definition.decisionProviderVersion(),
                definition.maxSteps(),
                definition.maxToolCalls(),
                definition.timeout().toMillis(),
                tools.versionsFor(capabilities.registeredTools()),
                capabilities);
    }

    private <T> T invoke(
            CheckedSupplier<T> supplier,
            long deadlineNanos,
            CancellationToken cancellationToken) throws CallFailure {
        Future<T> future;
        try {
            future = executor.submit(supplier::get);
        } catch (RejectedExecutionException rejected) {
            throw CallFailure.failed("RUNTIME_SATURATED", rejected);
        }
        return await(future, deadlineNanos, cancellationToken);
    }

    private <T> T await(
            Future<T> future,
            long deadlineNanos,
            CancellationToken cancellationToken) throws CallFailure {
        try {
            while (true) {
                CancellationCause cause = cancellationToken.cause();
                if (cause != null) {
                    future.cancel(true);
                    throw CallFailure.cancelled(cause, null);
                }
                long remaining = remainingNanos(deadlineNanos);
                if (remaining <= 0) {
                    future.cancel(true);
                    throw CallFailure.failed("AGENT_DEADLINE_EXCEEDED", null);
                }
                try {
                    T result = future.get(
                            Math.min(remaining, CANCELLATION_CHECK_INTERVAL_NANOS),
                            TimeUnit.NANOSECONDS);
                    CancellationCause afterCall = cancellationToken.cause();
                    if (afterCall != null) {
                        throw CallFailure.cancelled(afterCall, null);
                    }
                    return result;
                } catch (TimeoutException pollAgain) {
                    // A short timed wait keeps remote and local cancellation responsive.
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            CancellationCause cause = cancellationToken.cause();
            if (cause == null) {
                cause = CancellationCause.SHUTDOWN;
                cancellationToken.cancel(cause);
            }
            Thread.currentThread().interrupt();
            throw CallFailure.cancelled(cause, interrupted);
        } catch (CancellationException cancelled) {
            CancellationCause cause = cancellationToken.cause();
            throw CallFailure.cancelled(
                    cause == null ? CancellationCause.SHUTDOWN : cause,
                    cancelled);
        } catch (ExecutionException execution) {
            Throwable failure = execution.getCause();
            if (failure instanceof AgentCancellationException cancelled) {
                throw CallFailure.cancelled(cancelled.cancellationCause(), cancelled);
            }
            CancellationCause cause = cancellationToken.cause();
            if (cause != null) {
                throw CallFailure.cancelled(cause, failure);
            }
            throw CallFailure.failed(callErrorCode(failure, "AGENT_STEP_FAILED"), failure);
        }
    }

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0, deadlineNanos - System.nanoTime());
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - startedNanos));
    }

    private static String callErrorCode(Throwable error, String fallback) {
        if (error instanceof AgentCallGuard.CallRejectedException rejected) {
            return rejected.code();
        }
        if (error instanceof io.seekflux.platform.agentruntime.domain.exception.AgentModelOutputException output) {
            return output.code();
        }
        if (error instanceof io.seekflux.platform.agentruntime.domain.exception.ContextOverflowException) {
            return "LLM_CONTEXT_OVERFLOW_EXHAUSTED";
        }
        if (error instanceof io.seekflux.platform.agentruntime.domain.exception.LlmStreamException stream) {
            return stream.code();
        }
        return fallback;
    }

    private static String resultDigest(AgentToolResult result) {
        return argumentsDigest(Map.of(
                "success", result.success(),
                "output", result.output(),
                "errorCode", result.errorCode() == null ? "" : result.errorCode(),
                "linkedTraceId", result.linkedTraceId() == null ? "" : result.linkedTraceId(),
                "externalReceipt", result.externalReceipt()));
    }

    private static ToolJournalStatus recoveredJournalStatus(AgentToolResult result) {
        if (result.success()) {
            return ToolJournalStatus.SUCCEEDED;
        }
        if ("AGENT_DEADLINE_EXCEEDED".equals(result.errorCode())) {
            return ToolJournalStatus.TIMED_OUT;
        }
        for (CancellationCause cause : CancellationCause.values()) {
            if (cause.name().equals(result.errorCode())) {
                return ToolJournalStatus.CANCELLED;
            }
        }
        return ToolJournalStatus.FAILED;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private final class EagerToolBatch implements EagerToolDispatcher {
        private final RunState run;
        private final AgentRunRequest request;
        private final Set<String> effectiveTools;
        private final int step;
        private final long deadlineNanos;
        private final CancellationToken cancellationToken;
        private final Map<Integer, PendingToolCall> pending = new ConcurrentHashMap<>();

        private EagerToolBatch(
                RunState run,
                AgentRunRequest request,
                Set<String> effectiveTools,
                int step,
                long deadlineNanos,
                CancellationToken cancellationToken) {
            this.run = run;
            this.request = request;
            this.effectiveTools = effectiveTools;
            this.step = step;
            this.deadlineNanos = deadlineNanos;
            this.cancellationToken = cancellationToken;
        }

        @Override
        public synchronized Dispatch dispatch(
                int index,
                String toolName,
                Map<String, Object> arguments) {
            if (index < 0 || toolName == null || toolName.isBlank() || arguments == null) {
                return new Dispatch(Disposition.INELIGIBLE, null);
            }
            PendingToolCall existing = pending.get(index);
            if (existing != null) {
                return new Dispatch(Disposition.DUPLICATE, existing.call().toolCallId());
            }
            if (cancellationToken.isCancelled() || remainingNanos(deadlineNanos) <= 0) {
                return new Dispatch(Disposition.REJECTED, null);
            }
            PreparedToolCall call;
            try {
                call = prepare(
                        new AgentDecision.ToolCall(toolName, arguments),
                        effectiveTools,
                        run.snapshot.toolSchemaVersions(),
                        request,
                        step,
                        index);
            } catch (RuntimeException invalidOrDenied) {
                return new Dispatch(Disposition.INELIGIBLE, null);
            }
            // A write call cannot leave the process before the decision journal and side-effect
            // ledger are durable. It follows the normal AR-4 execution path instead.
            if (call.tool().effect() == AgentTool.Effect.MUTATING) {
                return new Dispatch(Disposition.INELIGIBLE, call.toolCallId());
            }
            long started = System.nanoTime();
            CancellationToken toolToken = cancellationToken.child();
            try {
                observe(call, run.runId, ToolExecutionObserver.Source.EAGER,
                        ToolExecutionObserver.Phase.BEFORE, 0, "STARTED");
                Future<AgentToolInvocation> future = executor.submit(() -> {
                    AgentToolContext context = new AgentToolContext(
                            run.runId,
                            call.toolCallId(),
                            "tool-call:" + call.toolCallId(),
                            request,
                            call.arguments(),
                            Duration.ofNanos(remainingNanos(deadlineNanos)),
                            toolToken);
                    return callGuard.execute(
                            AgentCallGuard.CallType.TOOL,
                            () -> toolExecutor.execute(
                                    call.tool().name(), call.tool().schema().version(),
                                    call.arguments(), context));
                });
                pending.put(index, new PendingToolCall(
                        call, started, future, null, null, ToolExecutionObserver.Source.EAGER));
                run.publish(new PushEvent.ToolStarted(
                        run.runId, clock.instant(), call.toolCallId(), call.tool().name(),
                        call.index(), true));
                return new Dispatch(Disposition.STARTED, call.toolCallId());
            } catch (RejectedExecutionException saturated) {
                observe(call, run.runId, ToolExecutionObserver.Source.EAGER,
                        ToolExecutionObserver.Phase.FAILURE, 0, "RUNTIME_SATURATED");
                return new Dispatch(Disposition.REJECTED, call.toolCallId());
            }
        }

        private void retainOnly(List<PreparedToolCall> decided) {
            Map<Integer, PreparedToolCall> expected = decided.stream()
                    .collect(java.util.stream.Collectors.toMap(PreparedToolCall::index, call -> call));
            List.copyOf(pending.entrySet()).forEach(entry -> {
                PreparedToolCall decidedCall = expected.get(entry.getKey());
                if (decidedCall == null
                        || !decidedCall.toolCallId().equals(entry.getValue().call().toolCallId())) {
                    PendingToolCall removed = pending.remove(entry.getKey());
                    if (removed != null && removed.future() != null) {
                        removed.future().cancel(true);
                    }
                }
            });
        }

        private PendingToolCall take(PreparedToolCall call) {
            PendingToolCall candidate = pending.remove(call.index());
            if (candidate == null) {
                return null;
            }
            if (!candidate.call().toolCallId().equals(call.toolCallId())) {
                candidate.future().cancel(true);
                return null;
            }
            return candidate;
        }

        private void cancelAll() {
            List.copyOf(pending.values()).forEach(item -> {
                if (item.future() != null) {
                    item.future().cancel(true);
                }
            });
            pending.clear();
        }
    }

    private static final class CallFailure extends Exception {
        private final String code;
        private final CancellationCause cancellationCause;

        private CallFailure(String code, CancellationCause cancellationCause, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.cancellationCause = cancellationCause;
        }

        private static CallFailure cancelled(CancellationCause cause, Throwable error) {
            return new CallFailure(cause.name(), cause, error);
        }

        private static CallFailure failed(String code, Throwable error) {
            return new CallFailure(code, null, error);
        }
    }

    private static final class ToolPolicyFailure extends RuntimeException {
        private final String code;

        private ToolPolicyFailure(String code) {
            super(code);
            this.code = code;
        }
    }

    private record PreparedToolCall(
            String toolCallId,
            AgentTool tool,
            Map<String, Object> arguments,
            boolean argumentsRepaired,
            int step,
            int index,
            String approvalReason,
            Duration approvalTimeout) {
    }

    private record PendingToolCall(
            PreparedToolCall call,
            long startedNanos,
            Future<AgentToolInvocation> future,
            String immediateError,
            SideEffectLedgerEntry ledger,
            ToolExecutionObserver.Source source) {
    }

    private record ResumeBatch(
            int nextStep,
            int toolCallCount,
            String failureCode,
            CancellationCause cancellationCause) {
    }

    private final class RunState {
        private final Map<String, ToolCallJournalEntry> decisions = new HashMap<>();
        private final Map<String, AgentToolObservation> durableResults = new HashMap<>();
        private final String runId;
        private final AgentRunRequest request;
        private AgentRunTrace.DefinitionSnapshot snapshot;
        private final Instant startedAt;
        private final long startedNanos;
        private final long deadlineNanos;
        private final AgentRecoveryExecution recovery;
        private final PushEventPublisher publisher;
        private final List<AgentRunTrace.StepTrace> steps = new ArrayList<>();
        private final List<AgentMessage> messages = new ArrayList<>();
        private final Map<Integer, AgentAssistantContent> assistantContents = new ConcurrentHashMap<>();
        private List<AgentToolObservation> latestObservations = List.of();
        private Set<String> latestCompletedInvocations = Set.of();
        private int latestNextStep = 1;
        private int latestToolCallCount;
        private io.seekflux.platform.agentruntime.domain.model.run.LlmUsage llmUsage =
                io.seekflux.platform.agentruntime.domain.model.run.LlmUsage.UNMEASURED;
        private int sequence;

        private RunState(
                String runId,
                AgentRunRequest request,
                AgentRunTrace.DefinitionSnapshot snapshot,
                Instant startedAt,
                long startedNanos,
                long deadlineNanos,
                AgentRecoveryExecution recovery,
                RuntimeCheckpoint restored,
                PushEventPublisher publisher) {
            this.runId = runId;
            this.request = request;
            this.snapshot = snapshot;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
            this.deadlineNanos = deadlineNanos;
            this.recovery = recovery;
            this.publisher = publisher;
            if (restored != null) {
                this.steps.addAll(restored.steps());
                this.messages.addAll(restored.messages());
                this.llmUsage = restored.llmUsage();
                this.latestObservations = restored.observations();
                this.latestCompletedInvocations = restored.completedInvocations();
                this.latestNextStep = restored.nextStep();
                this.latestToolCallCount = restored.toolCallCount();
            }
        }

        private void record(AgentRunEvent.Type type, Map<String, Object> payload) {
            recorder.record(new AgentRunEvent(
                    UUID.randomUUID(),
                    runId,
                    request.requestId(),
                    request.sessionId(),
                    request.turnId(),
                    sequence++,
                    type,
                    clock.instant(),
                    payload));
        }

        private void publish(PushEvent event) {
            try {
                publisher.publish(event);
            } catch (RuntimeException ignored) {
                // Process projections cannot alter durable runtime execution semantics.
            }
        }

        private void recordUsage(io.seekflux.platform.agentruntime.domain.model.run.LlmUsage usage) {
            llmUsage = llmUsage.plus(usage);
        }

        private CapabilitySnapshot capabilities() {
            return snapshot.capabilities();
        }

        private void switchToolGroups(Set<String> activeGroups) {
            CapabilitySnapshot updated = capabilities().switchToolGroups(activeGroups);
            snapshot = new AgentRunTrace.DefinitionSnapshot(
                    snapshot.id(), snapshot.version(), snapshot.plannerVersion(),
                    snapshot.promptVersion(), snapshot.decisionProviderVersion(),
                    snapshot.maxSteps(), snapshot.maxToolCalls(), snapshot.timeoutMillis(),
                    snapshot.toolSchemaVersions(), updated);
            capabilityResolver.recordToolGroupSwitch(snapshot.id(), updated);
            record(AgentRunEvent.Type.CAPABILITIES_CHANGED, Map.of(
                    "catalogVersion", updated.catalogVersion(),
                    "activeToolGroups", updated.activeToolGroups(),
                    "effectiveTools", updated.effectiveTools()));
            publish(new PushEvent.CapabilitiesChanged(
                    runId, clock.instant(), updated.catalogVersion(),
                    updated.activeToolGroups(), updated.effectiveTools()));
        }

        private void recordAssistantContent(int step, AgentAssistantContent content) {
            assistantContents.put(step, content == null ? AgentAssistantContent.EMPTY : content);
        }

        private AgentMessage.Assistant recordAssistant(
                int step,
                AgentDecision decision,
                List<PreparedToolCall> preparedCalls) {
            AgentAssistantContent captured = assistantContents.getOrDefault(
                    step, AgentAssistantContent.EMPTY);
            String content = captured.content() == null
                    ? renderDecision(decision)
                    : captured.content();
            List<AgentMessage.ToolCall> messageCalls = preparedCalls.stream()
                    .map(call -> new AgentMessage.ToolCall(
                            call.toolCallId(),
                            call.tool().name(),
                            call.index(),
                            call.arguments()))
                    .toList();
            AgentMessage.Assistant message = new AgentMessage.Assistant(
                    1,
                    messageId("assistant:" + step),
                    request.requestId(),
                    request.turnId(),
                    runId,
                    step,
                    content,
                    captured.reasoning(),
                    captured.reasoningReplayable(),
                    messageCalls);
            messages.add(message);
            return message;
        }

        private ToolCallJournalEntry journalEntry(
                int step,
                PreparedToolCall call,
                AgentMessage.Assistant assistant,
                ToolJournalStatus status,
                AgentToolObservation observation) {
            return new ToolCallJournalEntry(
                    1,
                    request.sessionId(),
                    request.requestId(),
                    request.turnId(),
                    runId,
                    step,
                    call.index(),
                    call.toolCallId(),
                    call.tool().name(),
                    call.tool().schema().version(),
                    call.tool().effect(),
                    status,
                    call.arguments(),
                    argumentsDigest(call.arguments()),
                    call.argumentsRepaired(),
                    assistant,
                    observation,
                    clock.instant());
        }

        private RuntimeCheckpoint savePreTurn(
                int nextStep,
                int toolCallCount,
                List<AgentToolObservation> observations,
                Set<String> completedInvocations) {
            RuntimeCheckpoint checkpoint = checkpoint(
                    CheckpointBoundary.PRE_TURN,
                    nextStep,
                    toolCallCount,
                    observations,
                    completedInvocations,
                    null);
            recovery.savePreTurn(checkpoint);
            remember(checkpoint);
            publish(new PushEvent.CheckpointSaved(
                    runId, clock.instant(), checkpoint.boundary().name(), checkpoint.nextStep()));
            return checkpoint;
        }

        private void recordToolDecision(
                RuntimeCheckpoint checkpoint,
                List<ToolCallJournalEntry> calls) {
            RuntimeCheckpoint decisionState = checkpoint(CheckpointBoundary.PRE_TURN,
                    checkpoint.nextStep(), checkpoint.toolCallCount(), checkpoint.observations(),
                    checkpoint.completedInvocations(), null);
            recovery.recordToolDecision(decisionState, calls);
            calls.forEach(call -> decisions.put(call.toolCallId(), call));
        }

        private void recordJournalResult(ToolCallJournalEntry call) {
            if (durableResults.containsKey(call.toolCallId())) return;
            AgentMessage.ToolResult message = messages.stream()
                    .filter(AgentMessage.ToolResult.class::isInstance)
                    .map(AgentMessage.ToolResult.class::cast)
                    .filter(result -> result.toolCallId().equals(call.toolCallId()))
                    .findFirst().orElseGet(() -> toolResultMessage(
                            call.attemptId(), call.step(), call.toolCallId(), call.toolName(),
                            call.toolSchemaVersion(), messageStatus(call.status()),
                            call.observation().result().output(), call.observation().result().errorCode(),
                            call.observation().result().linkedTraceId(), call.argumentsRepaired(),
                            call.observation().tookMillis()));
            recovery.recordToolResult(call, message);
            durableResults.put(call.toolCallId(), call.observation());
        }

        private void savePostTurn(
                int nextStep,
                int toolCallCount,
                List<AgentToolObservation> observations,
                Set<String> completedInvocations) {
            RuntimeCheckpoint checkpoint = checkpoint(
                    CheckpointBoundary.POST_TURN,
                    nextStep,
                    toolCallCount,
                    observations,
                    completedInvocations,
                    null);
            recovery.savePostTurn(checkpoint);
            remember(checkpoint);
            publish(new PushEvent.CheckpointSaved(
                    runId, clock.instant(), checkpoint.boundary().name(), checkpoint.nextStep()));
        }

        private void saveTerminal(AgentRunResult result) {
            CheckpointBoundary boundary = result.state() == AgentTerminalState.NEED_CLARIFICATION
                    || result.state() == AgentTerminalState.WAITING
                    ? CheckpointBoundary.SUSPENDED
                    : CheckpointBoundary.COMPLETED;
            RuntimeCheckpoint checkpoint = checkpoint(
                    boundary,
                    latestNextStep,
                    latestToolCallCount,
                    latestObservations,
                    latestCompletedInvocations,
                    result);
            recovery.saveTerminal(checkpoint);
            publish(new PushEvent.CheckpointSaved(
                    runId, clock.instant(), checkpoint.boundary().name(), checkpoint.nextStep()));
        }

        private void saveWait(
                AgentRunResult result,
                WaitState waitState,
                ToolCallJournalEntry waitingCall) {
            RuntimeCheckpoint checkpoint = checkpoint(
                    CheckpointBoundary.SUSPENDED,
                    latestNextStep,
                    latestToolCallCount,
                    latestObservations,
                    latestCompletedInvocations,
                    result);
            if (!checkpoint.checkpointId().equals(waitState.checkpointId())) {
                throw new IllegalStateException("WaitState checkpoint identity does not match");
            }
            recovery.suspendWait(checkpoint, waitState, waitingCall);
            publish(new PushEvent.CheckpointSaved(
                    runId, clock.instant(), checkpoint.boundary().name(), checkpoint.nextStep()));
            publish(new PushEvent.WaitSuspended(runId, clock.instant(), waitState));
        }

        private RuntimeCheckpoint checkpoint(
                CheckpointBoundary boundary,
                int nextStep,
                int toolCallCount,
                List<AgentToolObservation> observations,
                Set<String> completedInvocations,
                AgentRunResult terminalResult) {
            String checkpointId = checkpointId(boundary, nextStep);
            return new RuntimeCheckpoint(
                    1,
                    checkpointId,
                    boundary,
                    request.sessionId(),
                    request.requestId(),
                    runId,
                    request.turnId(),
                    recovery.fencingToken(),
                    recovery.messageCutoff(),
                    snapshot,
                    nextStep,
                    toolCallCount,
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos(deadlineNanos)),
                    recovery.persistentFeatures(),
                    observations,
                    messages,
                    completedInvocations,
                    llmUsage,
                    steps,
                    terminalResult,
                    clock.instant());
        }

        private String checkpointId(CheckpointBoundary boundary, int nextStep) {
            String checkpointIdentity = request.sessionId() + ":" + request.requestId() + ":"
                    + boundary + ":" + nextStep + ":" + messages.size();
            return UUID.nameUUIDFromBytes(
                    checkpointIdentity.getBytes(StandardCharsets.UTF_8)).toString();
        }

        private void remember(RuntimeCheckpoint checkpoint) {
            latestNextStep = checkpoint.nextStep();
            latestToolCallCount = checkpoint.toolCallCount();
            latestObservations = checkpoint.observations();
            latestCompletedInvocations = checkpoint.completedInvocations();
        }

        private void recordToolResult(int step, AgentToolObservation observation) {
            AgentMessage.ToolResultStatus status = messageStatus(recoveredJournalStatus(observation.result()));
            messages.add(toolResultMessage(
                    step,
                    observation.toolCallId(),
                    observation.toolName(),
                    observation.schemaVersion(),
                    status,
                    observation.result().output(),
                    observation.result().errorCode(),
                    observation.result().linkedTraceId(),
                    observation.argumentsRepaired(),
                    observation.tookMillis()));
        }

        private void recordToolResult(
                int step,
                PreparedToolCall call,
                AgentMessage.ToolResultStatus status,
                String errorCode) {
            messages.add(toolResultMessage(
                    step,
                    call.toolCallId(),
                    call.tool().name(),
                    call.tool().schema().version(),
                    status,
                    Map.of(),
                    errorCode,
                    null,
                    call.argumentsRepaired(),
                    0));
        }

        private AgentMessage.ToolResult toolResultMessage(
                int step,
                String toolCallId,
                String toolName,
                String schemaVersion,
                AgentMessage.ToolResultStatus status,
                Map<String, Object> output,
                String errorCode,
                String linkedTraceId,
                boolean argumentsRepaired,
                long tookMillis) {
            return toolResultMessage(
                    runId, step, toolCallId, toolName, schemaVersion, status, output,
                    errorCode, linkedTraceId, argumentsRepaired, tookMillis);
        }

        private AgentMessage.ToolResult toolResultMessage(
                String resultAttemptId,
                int step,
                String toolCallId,
                String toolName,
                String schemaVersion,
                AgentMessage.ToolResultStatus status,
                Map<String, Object> output,
                String errorCode,
                String linkedTraceId,
                boolean argumentsRepaired,
                long tookMillis) {
            Map<String, Object> contents = output == null ? Map.of() : output;
            String modelContent = status == AgentMessage.ToolResultStatus.SUCCEEDED
                    ? toolName + ":" + new TreeMap<>(contents)
                    : toolName + ":" + status.name() + ":" + errorCode;
            return new AgentMessage.ToolResult(
                    1,
                    messageId("tool-result:" + toolCallId),
                    request.requestId(),
                    request.turnId(),
                    resultAttemptId,
                    step,
                    toolCallId,
                    toolName,
                    schemaVersion,
                    status,
                    modelContent,
                    contents,
                    contents,
                    contents,
                    List.of(),
                    errorCode,
                    linkedTraceId,
                    argumentsRepaired,
                    tookMillis);
        }

        private String messageId(String suffix) {
            String identity = request.sessionId() + ":" + request.requestId() + ":"
                    + request.turnId() + ":" + suffix;
            return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private static String renderDecision(AgentDecision decision) {
        if (decision instanceof AgentDecision.Complete complete) {
            return "complete:" + new TreeMap<>(complete.output());
        }
        if (decision instanceof AgentDecision.Clarify clarify) {
            return clarify.question();
        }
        if (decision instanceof AgentDecision.Fallback fallback) {
            return "fallback:" + fallback.reason();
        }
        if (decision instanceof AgentDecision.CallTool call) {
            return "tool_call:" + call.toolName() + ":" + new TreeMap<>(call.arguments());
        }
        AgentDecision.CallTools calls = (AgentDecision.CallTools) decision;
        return "tool_calls:" + calls.calls();
    }
}
