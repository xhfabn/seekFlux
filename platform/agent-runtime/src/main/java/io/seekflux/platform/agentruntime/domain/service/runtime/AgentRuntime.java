package io.seekflux.platform.agentruntime.domain.service.runtime;

import io.seekflux.platform.agentruntime.application.spi.capability.event.AgentRunRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.tool.AgentToolExecutor;
import io.seekflux.platform.agentruntime.domain.service.tool.AgentToolRegistry;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
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
import io.seekflux.platform.agentruntime.application.spi.business.tool.model.AgentToolContext;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolInvocation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolObservation;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.service.execution.AgentCallGuard;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
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
        this.tools = Objects.requireNonNull(tools, "tool registry must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "tool executor must not be null");
        this.executor = Objects.requireNonNull(executor, "agent executor must not be null");
        this.recorder = Objects.requireNonNull(recorder, "run recorder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.callGuard = Objects.requireNonNull(callGuard, "call guard must not be null");
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
        Objects.requireNonNull(definition, "agent definition must not be null");
        Objects.requireNonNull(request, "agent run request must not be null");
        Objects.requireNonNull(planner, "agent planner must not be null");
        Objects.requireNonNull(cancellationToken, "cancellation token must not be null");
        Objects.requireNonNull(recovery, "recovery execution must not be null");

        RecoveryPlan recoveryPlan = recovery.plan();
        if (recoveryPlan.action() == ResumeAction.COMMIT_TERMINAL) {
            return recoveryPlan.checkpoint().terminalResult();
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
        Set<String> effectiveTools = effectiveTools(definition, request);
        AgentRunTrace.DefinitionSnapshot snapshot = new AgentRunTrace.DefinitionSnapshot(
                definition.id(),
                definition.version(),
                definition.plannerVersion(),
                definition.promptVersion(),
                definition.decisionProviderVersion(),
                definition.maxSteps(),
                definition.maxToolCalls(),
                definition.timeout().toMillis(),
                tools.versionsFor(effectiveTools));
        if (restored != null) {
            validateCheckpoint(restored, request, snapshot);
        }
        RunState run = new RunState(
                runId, request, snapshot, startedAt, startedNanos, deadlineNanos, recovery, restored);
        run.record(AgentRunEvent.Type.RUN_STARTED, Map.of("definition", snapshot));

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

            RuntimeCheckpoint preTurnCheckpoint = run.savePreTurn(
                    step,
                    toolCalls,
                    observations,
                    completedInvocations);

            AgentDecision decision;
            long decisionStarted = System.nanoTime();
            int currentStep = step;
            try {
                AgentDecisionContext context = new AgentDecisionContext(
                        request,
                        step,
                        Duration.ofNanos(remainingNanos(deadlineNanos)),
                        observations,
                        run::recordUsage,
                        content -> run.recordAssistantContent(currentStep, content));
                decision = invoke(
                        () -> callGuard.execute(AgentCallGuard.CallType.MODEL, () -> planner.decide(context)),
                        deadlineNanos,
                        cancellationToken);
            } catch (CallFailure failure) {
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
                return finishCancelled(run, decisionCancellation);
            }
            if (decision == null) {
                return finishFailure(run, definition, "PLANNER_RETURNED_NULL", null);
            }
            run.record(AgentRunEvent.Type.DECISION_MADE, decisionPayload(step, decision));

            if (decision instanceof AgentDecision.Complete complete) {
                run.recordAssistant(step, decision, List.of());
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "COMPLETE", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), null));
                return finish(run, AgentTerminalState.RESULTS_READY, complete.output(), null,
                        null, false, "AGENT");
            }
            if (decision instanceof AgentDecision.Clarify clarify) {
                run.recordAssistant(step, decision, List.of());
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "CLARIFY", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), null));
                return finish(run, AgentTerminalState.NEED_CLARIFICATION, Map.of(), clarify.question(),
                        null, false, "AGENT");
            }
            if (decision instanceof AgentDecision.Fallback fallback) {
                run.recordAssistant(step, decision, List.of());
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "FALLBACK", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), fallback.reason()));
                return finishFailure(run, definition, fallback.reason(), null);
            }

            List<AgentDecision.ToolCall> calls = toolCalls(decision);
            if (toolCalls + calls.size() > definition.maxToolCalls()) {
                run.recordAssistant(step, decision, List.of());
                return finishFailure(run, definition, "TOOL_CALL_LIMIT_REACHED", null);
            }
            toolCalls += calls.size();
            List<PreparedToolCall> prepared;
            try {
                List<PreparedToolCall> preparedCalls = new ArrayList<>();
                for (int callIndex = 0; callIndex < calls.size(); callIndex++) {
                    preparedCalls.add(prepare(
                            calls.get(callIndex), effectiveTools, request, step, callIndex));
                }
                prepared = List.copyOf(preparedCalls);
            } catch (IllegalArgumentException invalidArguments) {
                run.recordAssistant(step, decision, List.of());
                return finishFailure(run, definition, "TOOL_ARGUMENT_INVALID", null);
            }
            AgentMessage.Assistant assistant = run.recordAssistant(step, decision, prepared);
            int journalStep = step;
            List<ToolCallJournalEntry> journalEntries = prepared.stream()
                    .map(call -> run.journalEntry(
                            journalStep, call, assistant, ToolJournalStatus.DECIDED, null))
                    .toList();
            run.recordToolDecision(preTurnCheckpoint, journalEntries);
            boolean noProgress = false;
            for (PreparedToolCall call : prepared) {
                String fingerprint = call.tool().name() + ":" + new TreeMap<>(call.arguments());
                if (!completedInvocations.add(fingerprint)) {
                    noProgress = true;
                }
            }
            if (noProgress) {
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
                run.markToolExecuting(journalEntries);
                completed = executeBatch(runId, request, prepared, deadlineNanos, cancellationToken);
            } catch (CallFailure failure) {
                if (failure.cancellationCause != null
                        || "AGENT_DEADLINE_EXCEEDED".equals(failure.code)) {
                    AgentMessage.ToolResultStatus messageStatus = failure.cancellationCause == null
                            ? AgentMessage.ToolResultStatus.TIMED_OUT
                            : AgentMessage.ToolResultStatus.CANCELLED;
                    for (PreparedToolCall call : prepared) {
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

    private AgentRunResult finish(
            RunState run,
            AgentTerminalState state,
            Map<String, Object> output,
            String clarification,
            String fallbackReason,
            boolean degraded,
            String executionMode) {
        return finish(run, state, output, clarification, fallbackReason, null, degraded, executionMode);
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
        run.record(AgentRunEvent.Type.RUN_COMPLETED, payload);
        AgentRunResult result = new AgentRunResult(
                state, output, clarification, fallbackReason, cancellationReason, degraded,
                run.messages, trace);
        run.saveTerminal(result);
        return result;
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
                if (entry.status() == ToolJournalStatus.UNKNOWN && !entry.safeToRetry()) {
                    throw new IllegalStateException("unsafe Tool reached runtime recovery dispatch");
                }
                pending.add(restorePrepared(entry));
            }
        }

        Map<String, AgentToolObservation> newlyCompleted = new HashMap<>();
        CallFailure batchFailure = null;
        if (!pending.isEmpty()) {
            run.recovery.markToolExecuting(
                    run.request.sessionId(),
                    run.request.requestId(),
                    run.runId,
                    pending.stream().map(PreparedToolCall::toolCallId).toList());
            try {
                for (AgentToolObservation observation : executeBatch(
                        run.runId,
                        run.request,
                        pending,
                        deadlineNanos,
                        cancellationToken)) {
                    newlyCompleted.put(observation.toolCallId(), observation);
                }
            } catch (CallFailure failure) {
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
                    journalStatus = observation.result().success()
                            ? ToolJournalStatus.SUCCEEDED
                            : ToolJournalStatus.FAILED;
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
        if (batchFailure != null && batchFailure.cancellationCause == null) {
            recoveredFailure = batchFailure.code;
        } else if (recoveredTimeout) {
            recoveredFailure = "AGENT_DEADLINE_EXCEEDED";
        } else if (!anySucceeded && recoveredCancellation == null) {
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
        AgentTool tool = tools.require(entry.toolName());
        if (!tool.schema().version().equals(entry.toolSchemaVersion())) {
            throw new IllegalStateException("checkpoint Tool schema version no longer matches");
        }
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
                entry.callIndex());
    }

    private static AgentMessage.ToolResultStatus messageStatus(ToolJournalStatus status) {
        return switch (status) {
            case SUCCEEDED -> AgentMessage.ToolResultStatus.SUCCEEDED;
            case FAILED -> AgentMessage.ToolResultStatus.FAILED;
            case CANCELLED -> AgentMessage.ToolResultStatus.CANCELLED;
            case TIMED_OUT -> AgentMessage.ToolResultStatus.TIMED_OUT;
            case DECIDED, EXECUTING, UNKNOWN -> AgentMessage.ToolResultStatus.WAITING;
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
            AgentRunTrace.DefinitionSnapshot definition) {
        if (!checkpoint.sessionId().equals(request.sessionId())
                || !checkpoint.requestId().equals(request.requestId())
                || !checkpoint.turnId().equals(request.turnId())) {
            throw new IllegalStateException("checkpoint identity does not match the execution request");
        }
        if (!checkpoint.definition().equals(definition)) {
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

    private List<AgentToolObservation> executeBatch(
            String runId,
            AgentRunRequest request,
            List<PreparedToolCall> calls,
            long deadlineNanos,
            CancellationToken cancellationToken) throws CallFailure {
        CancellationToken batchToken = cancellationToken.child();
        List<PendingToolCall> pending = new ArrayList<>();
        for (PreparedToolCall call : calls) {
            CancellationCause cause = batchToken.cause();
            if (cause != null) {
                cancelPending(pending);
                throw CallFailure.cancelled(cause, null);
            }
            long started = System.nanoTime();
            if (remainingNanos(deadlineNanos) <= 0) {
                pending.add(new PendingToolCall(call, started, null, "AGENT_DEADLINE_EXCEEDED"));
                continue;
            }
            try {
                CancellationToken toolToken = batchToken.child();
                Future<AgentToolInvocation> future = executor.submit(() -> {
                    AgentToolContext context = new AgentToolContext(
                            runId,
                            call.toolCallId(),
                            request,
                            call.arguments(),
                            Duration.ofNanos(remainingNanos(deadlineNanos)),
                            toolToken);
                    return callGuard.execute(
                            AgentCallGuard.CallType.TOOL,
                            () -> toolExecutor.execute(call.tool().name(), call.arguments(), context));
                });
                pending.add(new PendingToolCall(call, started, future, null));
            } catch (RejectedExecutionException rejected) {
                pending.add(new PendingToolCall(call, started, null, "RUNTIME_SATURATED"));
            }
        }

        List<AgentToolObservation> observations = new ArrayList<>();
        for (PendingToolCall item : pending) {
            AgentToolResult result;
            if (item.immediateError() != null) {
                result = AgentToolResult.failure(item.immediateError());
            } else {
                long remaining = remainingNanos(deadlineNanos);
                if (remaining <= 0) {
                    cancelPending(pending);
                    throw CallFailure.failed("AGENT_DEADLINE_EXCEEDED", null);
                } else {
                    try {
                        result = await(item.future(), deadlineNanos, batchToken).result();
                    } catch (CallFailure failure) {
                        if (failure.cancellationCause != null
                                || "AGENT_DEADLINE_EXCEEDED".equals(failure.code)) {
                            cancelPending(pending);
                            throw failure;
                        }
                        result = AgentToolResult.failure(failure.code);
                    }
                }
            }
            PreparedToolCall call = item.call();
            observations.add(new AgentToolObservation(
                    call.toolCallId(),
                    call.tool().name(),
                    call.tool().schema().version(),
                    call.arguments(),
                    call.argumentsRepaired(),
                    result,
                    elapsedMillis(item.startedNanos())));
        }
        return List.copyOf(observations);
    }

    private static void cancelPending(List<PendingToolCall> pending) {
        for (PendingToolCall item : pending) {
            if (item.future() != null) {
                item.future().cancel(true);
            }
        }
    }

    private PreparedToolCall prepare(
            AgentDecision.ToolCall call,
            Set<String> effectiveTools,
            AgentRunRequest request,
            int step,
            int callIndex) {
        if (!effectiveTools.contains(call.toolName())) {
            throw new IllegalArgumentException("tool is not exposed for this request");
        }
        AgentTool tool = tools.require(call.toolName());
        Map<String, Object> arguments = call.arguments();
        boolean repaired = false;
        try {
            tool.schema().validate(arguments);
        } catch (IllegalArgumentException invalid) {
            arguments = tool.schema().repair(arguments);
            tool.schema().validate(arguments);
            repaired = true;
        }
        String identity = request.requestId() + ":" + step + ":" + callIndex + ":"
                + tool.name() + ":" + new TreeMap<>(arguments);
        String toolCallId = UUID.nameUUIDFromBytes(
                identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return new PreparedToolCall(toolCallId, tool, arguments, repaired, callIndex);
    }

    private static List<AgentDecision.ToolCall> toolCalls(AgentDecision decision) {
        if (decision instanceof AgentDecision.CallTool call) {
            return List.of(new AgentDecision.ToolCall(call.toolName(), call.arguments()));
        }
        return ((AgentDecision.CallTools) decision).calls();
    }

    private static Set<String> effectiveTools(AgentDefinition definition, AgentRunRequest request) {
        Object configured = request.attributes().get("allowedTools");
        if (!(configured instanceof List<?> values)) {
            return definition.allowedTools();
        }
        Set<String> requested = values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (requested.isEmpty() || !definition.allowedTools().containsAll(requested)) {
            throw new IllegalArgumentException("request contains an invalid dynamic tool set");
        }
        return requested;
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
        return fallback;
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
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

    private record PreparedToolCall(
            String toolCallId,
            AgentTool tool,
            Map<String, Object> arguments,
            boolean argumentsRepaired,
            int index) {
    }

    private record PendingToolCall(
            PreparedToolCall call,
            long startedNanos,
            Future<AgentToolInvocation> future,
            String immediateError) {
    }

    private record ResumeBatch(
            int nextStep,
            int toolCallCount,
            String failureCode,
            CancellationCause cancellationCause) {
    }

    private final class RunState {
        private final String runId;
        private final AgentRunRequest request;
        private final AgentRunTrace.DefinitionSnapshot snapshot;
        private final Instant startedAt;
        private final long startedNanos;
        private final long deadlineNanos;
        private final AgentRecoveryExecution recovery;
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
                RuntimeCheckpoint restored) {
            this.runId = runId;
            this.request = request;
            this.snapshot = snapshot;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
            this.deadlineNanos = deadlineNanos;
            this.recovery = recovery;
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

        private void recordUsage(io.seekflux.platform.agentruntime.domain.model.run.LlmUsage usage) {
            llmUsage = llmUsage.plus(usage);
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
            return checkpoint;
        }

        private void recordToolDecision(
                RuntimeCheckpoint checkpoint,
                List<ToolCallJournalEntry> calls) {
            recovery.recordToolDecision(checkpoint, calls);
        }

        private void markToolExecuting(List<ToolCallJournalEntry> calls) {
            recovery.markToolExecuting(
                    request.sessionId(),
                    request.requestId(),
                    runId,
                    calls.stream().map(ToolCallJournalEntry::toolCallId).toList());
        }

        private void recordJournalResult(ToolCallJournalEntry call) {
            recovery.recordToolResult(call);
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
        }

        private void saveTerminal(AgentRunResult result) {
            CheckpointBoundary boundary = result.state() == AgentTerminalState.NEED_CLARIFICATION
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
        }

        private RuntimeCheckpoint checkpoint(
                CheckpointBoundary boundary,
                int nextStep,
                int toolCallCount,
                List<AgentToolObservation> observations,
                Set<String> completedInvocations,
                AgentRunResult terminalResult) {
            String checkpointIdentity = request.sessionId() + ":" + request.requestId() + ":"
                    + boundary + ":" + nextStep + ":" + messages.size();
            String checkpointId = UUID.nameUUIDFromBytes(
                    checkpointIdentity.getBytes(StandardCharsets.UTF_8)).toString();
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

        private void remember(RuntimeCheckpoint checkpoint) {
            latestNextStep = checkpoint.nextStep();
            latestToolCallCount = checkpoint.toolCallCount();
            latestObservations = checkpoint.observations();
            latestCompletedInvocations = checkpoint.completedInvocations();
        }

        private void recordToolResult(int step, AgentToolObservation observation) {
            AgentMessage.ToolResultStatus status = observation.result().success()
                    ? AgentMessage.ToolResultStatus.SUCCEEDED
                    : AgentMessage.ToolResultStatus.FAILED;
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
