package io.seekflux.platform.agentruntime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class AgentRuntime {

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
        Objects.requireNonNull(definition, "agent definition must not be null");
        Objects.requireNonNull(request, "agent run request must not be null");
        Objects.requireNonNull(planner, "agent planner must not be null");

        String runId = UUID.randomUUID().toString();
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        long deadlineNanos = saturatingAdd(startedNanos, definition.timeout().toNanos());
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
        RunState run = new RunState(runId, request, snapshot, startedAt, startedNanos);
        run.record(AgentRunEvent.Type.RUN_STARTED, Map.of("definition", snapshot));

        List<AgentToolObservation> observations = new ArrayList<>();
        Set<String> completedInvocations = new HashSet<>();
        int toolCalls = 0;
        for (int step = 1; step <= definition.maxSteps(); step++) {
            if (remainingNanos(deadlineNanos) <= 0) {
                return finishFailure(run, definition, "AGENT_DEADLINE_EXCEEDED", null);
            }

            AgentDecision decision;
            long decisionStarted = System.nanoTime();
            try {
                AgentDecisionContext context = new AgentDecisionContext(
                        request,
                        step,
                        Duration.ofNanos(remainingNanos(deadlineNanos)),
                        observations,
                        run::recordUsage);
                decision = invoke(
                        () -> callGuard.execute(AgentCallGuard.CallType.MODEL, () -> planner.decide(context)),
                        deadlineNanos);
            } catch (CallFailure failure) {
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "PLAN", "FAILED", null, null, null,
                        elapsedMillis(decisionStarted), failure.code));
                run.record(AgentRunEvent.Type.DECISION_MADE, Map.of(
                        "step", step,
                        "status", "FAILED",
                        "errorCode", failure.code));
                if (failure.cancelled) {
                    return finish(run, AgentTerminalState.CANCELLED, Map.of(), null,
                            failure.code, true, "CANCELLED");
                }
                return finishFailure(run, definition, failure.code, null);
            }

            if (decision == null) {
                return finishFailure(run, definition, "PLANNER_RETURNED_NULL", null);
            }
            run.record(AgentRunEvent.Type.DECISION_MADE, decisionPayload(step, decision));

            if (decision instanceof AgentDecision.Complete complete) {
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "COMPLETE", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), null));
                return finish(run, AgentTerminalState.RESULTS_READY, complete.output(), null,
                        null, false, "AGENT");
            }
            if (decision instanceof AgentDecision.Clarify clarify) {
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "CLARIFY", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), null));
                return finish(run, AgentTerminalState.NEED_CLARIFICATION, Map.of(), clarify.question(),
                        null, false, "AGENT");
            }
            if (decision instanceof AgentDecision.Fallback fallback) {
                run.steps.add(new AgentRunTrace.StepTrace(
                        step, "FALLBACK", "SUCCEEDED", null, null, null,
                        elapsedMillis(decisionStarted), fallback.reason()));
                return finishFailure(run, definition, fallback.reason(), null);
            }

            List<AgentDecision.ToolCall> calls = toolCalls(decision);
            if (toolCalls + calls.size() > definition.maxToolCalls()) {
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
                return finishFailure(run, definition, "TOOL_ARGUMENT_INVALID", null);
            }
            for (PreparedToolCall call : prepared) {
                String fingerprint = call.tool().name() + ":" + new TreeMap<>(call.arguments());
                if (!completedInvocations.add(fingerprint)) {
                    return finishFailure(run, definition, "NO_PROGRESS_DETECTED", null);
                }
            }

            List<AgentToolObservation> completed;
            try {
                completed = executeBatch(runId, request, prepared, deadlineNanos);
            } catch (CallFailure failure) {
                if (failure.cancelled) {
                    return finish(run, AgentTerminalState.CANCELLED, Map.of(), null,
                            failure.code, true, "CANCELLED");
                }
                return finishFailure(run, definition, failure.code, null);
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
            }
            if (completed.stream().noneMatch(observation -> observation.result().success())) {
                AgentToolResult first = completed.getFirst().result();
                return finishFailure(run, definition, first.errorCode(), first.linkedTraceId());
            }
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

    private AgentRunResult finish(
            RunState run,
            AgentTerminalState state,
            Map<String, Object> output,
            String clarification,
            String fallbackReason,
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
                run.llmUsage,
                run.steps);
        Map<String, Object> payload = new HashMap<>();
        payload.put("state", state.name());
        payload.put("executionMode", executionMode);
        payload.put("degraded", degraded);
        payload.put("trace", trace);
        if (fallbackReason != null) {
            payload.put("fallbackReason", fallbackReason);
        }
        run.record(AgentRunEvent.Type.RUN_COMPLETED, payload);
        return new AgentRunResult(state, output, clarification, fallbackReason, degraded, trace);
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

    private List<AgentToolObservation> executeBatch(
            String runId,
            AgentRunRequest request,
            List<PreparedToolCall> calls,
            long deadlineNanos) throws CallFailure {
        List<PendingToolCall> pending = new ArrayList<>();
        for (PreparedToolCall call : calls) {
            long started = System.nanoTime();
            if (remainingNanos(deadlineNanos) <= 0) {
                pending.add(new PendingToolCall(call, started, null, "AGENT_DEADLINE_EXCEEDED"));
                continue;
            }
            try {
                Future<AgentToolInvocation> future = executor.submit(() -> {
                    AgentToolContext context = new AgentToolContext(
                            runId,
                            call.toolCallId(),
                            request,
                            call.arguments(),
                            Duration.ofNanos(remainingNanos(deadlineNanos)));
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
                    item.future().cancel(true);
                    result = AgentToolResult.failure("AGENT_DEADLINE_EXCEEDED");
                } else {
                    try {
                        result = item.future().get(remaining, TimeUnit.NANOSECONDS).result();
                    } catch (TimeoutException timeout) {
                        item.future().cancel(true);
                        result = AgentToolResult.failure("AGENT_DEADLINE_EXCEEDED");
                    } catch (InterruptedException interrupted) {
                        item.future().cancel(true);
                        Thread.currentThread().interrupt();
                        throw new CallFailure("AGENT_CANCELLED", true, interrupted);
                    } catch (ExecutionException failed) {
                        result = AgentToolResult.failure(callErrorCode(failed.getCause(), "AGENT_STEP_FAILED"));
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
        return new PreparedToolCall(toolCallId, tool, arguments, repaired);
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

    private <T> T invoke(CheckedSupplier<T> supplier, long deadlineNanos) throws CallFailure {
        Future<T> future;
        try {
            future = executor.submit(supplier::get);
        } catch (RejectedExecutionException rejected) {
            throw new CallFailure("RUNTIME_SATURATED", false, rejected);
        }
        long remaining = remainingNanos(deadlineNanos);
        if (remaining <= 0) {
            future.cancel(true);
            throw new CallFailure("AGENT_DEADLINE_EXCEEDED", false, null);
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new CallFailure("AGENT_DEADLINE_EXCEEDED", false, timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallFailure("AGENT_CANCELLED", true, interrupted);
        } catch (ExecutionException execution) {
            throw new CallFailure(
                    callErrorCode(execution.getCause(), "AGENT_STEP_FAILED"),
                    false,
                    execution.getCause());
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
        private final boolean cancelled;

        private CallFailure(String code, boolean cancelled, Throwable cause) {
            super(code, cause);
            this.code = code;
            this.cancelled = cancelled;
        }
    }

    private record PreparedToolCall(
            String toolCallId,
            AgentTool tool,
            Map<String, Object> arguments,
            boolean argumentsRepaired) {
    }

    private record PendingToolCall(
            PreparedToolCall call,
            long startedNanos,
            Future<AgentToolInvocation> future,
            String immediateError) {
    }

    private final class RunState {
        private final String runId;
        private final AgentRunRequest request;
        private final AgentRunTrace.DefinitionSnapshot snapshot;
        private final Instant startedAt;
        private final long startedNanos;
        private final List<AgentRunTrace.StepTrace> steps = new ArrayList<>();
        private io.seekflux.platform.agentruntime.llm.LlmUsage llmUsage =
                io.seekflux.platform.agentruntime.llm.LlmUsage.UNMEASURED;
        private int sequence;

        private RunState(
                String runId,
                AgentRunRequest request,
                AgentRunTrace.DefinitionSnapshot snapshot,
                Instant startedAt,
                long startedNanos) {
            this.runId = runId;
            this.request = request;
            this.snapshot = snapshot;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
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

        private void recordUsage(io.seekflux.platform.agentruntime.llm.LlmUsage usage) {
            llmUsage = llmUsage.plus(usage);
        }
    }
}
