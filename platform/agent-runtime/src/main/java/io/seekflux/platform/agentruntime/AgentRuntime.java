package io.seekflux.platform.agentruntime;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public AgentRuntime(
            AgentToolRegistry tools,
            AgentToolExecutor toolExecutor,
            ExecutorService executor,
            AgentRunRecorder recorder,
            Clock clock) {
        this.tools = Objects.requireNonNull(tools, "tool registry must not be null");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "tool executor must not be null");
        this.executor = Objects.requireNonNull(executor, "agent executor must not be null");
        this.recorder = Objects.requireNonNull(recorder, "run recorder must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
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
        AgentRunTrace.DefinitionSnapshot snapshot = new AgentRunTrace.DefinitionSnapshot(
                definition.id(),
                definition.version(),
                definition.plannerVersion(),
                definition.promptVersion(),
                definition.decisionProviderVersion(),
                definition.maxSteps(),
                definition.maxToolCalls(),
                definition.timeout().toMillis(),
                tools.versionsFor(definition.allowedTools()));
        RunState run = new RunState(runId, request, snapshot, startedAt, startedNanos);
        run.record(AgentRunEvent.Type.RUN_STARTED, Map.of("definition", snapshot));

        List<AgentToolObservation> observations = new ArrayList<>();
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
                        observations);
                decision = invoke(() -> planner.decide(context), deadlineNanos);
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

            AgentDecision.CallTool call = (AgentDecision.CallTool) decision;
            if (++toolCalls > definition.maxToolCalls()) {
                return finishFailure(run, definition, "TOOL_CALL_LIMIT_REACHED", null);
            }
            if (!definition.allowedTools().contains(call.toolName())) {
                return finishFailure(run, definition, "TOOL_NOT_ALLOWED", null);
            }

            AgentTool tool;
            try {
                tool = tools.require(call.toolName());
                tool.schema().validate(call.arguments());
            } catch (IllegalArgumentException invalidArguments) {
                return finishFailure(run, definition, "TOOL_ARGUMENT_INVALID", null);
            }

            String toolCallId = UUID.randomUUID().toString();
            long toolStarted = System.nanoTime();
            AgentToolResult toolResult;
            try {
                AgentToolContext toolContext = new AgentToolContext(
                        runId,
                        toolCallId,
                        request,
                        call.arguments(),
                        Duration.ofNanos(remainingNanos(deadlineNanos)));
                AgentToolInvocation invocation = invoke(
                        () -> toolExecutor.execute(tool.name(), call.arguments(), toolContext),
                        deadlineNanos);
                toolResult = invocation.result();
            } catch (CallFailure failure) {
                toolResult = AgentToolResult.failure(failure.code);
            }
            long toolMillis = elapsedMillis(toolStarted);
            AgentToolObservation observation = new AgentToolObservation(
                    toolCallId,
                    tool.name(),
                    tool.schema().version(),
                    call.arguments(),
                    toolResult,
                    toolMillis);
            observations.add(observation);
            run.steps.add(new AgentRunTrace.StepTrace(
                    step,
                    "CALL_TOOL",
                    toolResult.success() ? "SUCCEEDED" : "FAILED",
                    toolCallId,
                    tool.name(),
                    toolResult.linkedTraceId(),
                    toolMillis,
                    toolResult.errorCode()));
            run.record(AgentRunEvent.Type.TOOL_COMPLETED, toolPayload(step, observation));
            if (!toolResult.success()) {
                return finishFailure(run, definition, toolResult.errorCode(), toolResult.linkedTraceId());
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
        }
        return payload;
    }

    private static Map<String, Object> toolPayload(int step, AgentToolObservation observation) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("step", step);
        payload.put("toolCallId", observation.toolCallId());
        payload.put("toolName", observation.toolName());
        payload.put("schemaVersion", observation.schemaVersion());
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
            throw new CallFailure("AGENT_STEP_FAILED", false, execution.getCause());
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

    private final class RunState {
        private final String runId;
        private final AgentRunRequest request;
        private final AgentRunTrace.DefinitionSnapshot snapshot;
        private final Instant startedAt;
        private final long startedNanos;
        private final List<AgentRunTrace.StepTrace> steps = new ArrayList<>();
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
    }
}
