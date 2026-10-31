package io.seekflux.platform.agentruntime.domain.service.observation;

import io.seekflux.platform.agentruntime.application.command.AgentRunRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.observation.*;
import io.seekflux.platform.agentruntime.domain.exception.AgentCancellationException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Synchronous, bounded capture; the supplied observer must be non-blocking. */
public final class AgentObservations {
    public static final AgentObservations NOOP =
            new AgentObservations(AgentExecutionObserver.NOOP, ObservationCapturePolicy.metadataOnly());
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private final AgentExecutionObserver observer;
    private final ObservationCapturePolicy policy;
    private final AgentObservationProcessor processor;
    private final LongAdder captureFailures = new LongAdder();
    private final LongAdder filtered = new LongAdder();

    public AgentObservations(AgentExecutionObserver observer, ObservationCapturePolicy policy) {
        this(observer, policy, AgentObservationProcessor.IDENTITY);
    }

    public AgentObservations(AgentExecutionObserver observer, ObservationCapturePolicy policy,
            AgentObservationProcessor processor) {
        this.observer = Objects.requireNonNull(observer);
        this.policy = Objects.requireNonNull(policy);
        this.processor = Objects.requireNonNull(processor);
    }

    public static String rootId(String runId) { return runId + ":run"; }
    public static String planId(String runId, int step) { return runId + ":plan:" + step; }

    public Scope start(AgentRunRequest request, String runId, String observationId, String parentId,
            String toolCallId, AgentObservationEvent.Kind kind, String name, Map<String, Object> input) {
        var identity = new AgentObservationEvent.Identity(runId, request.requestId(), request.sessionId(),
                request.turnId(), observationId, parentId, toolCallId, UUID.randomUUID().toString());
        Scope scope = new Scope(identity, kind, name);
        scope.emit(AgentObservationEvent.Phase.STARTED, "STARTED", input);
        return scope;
    }

    public long captureFailureCount() { return captureFailures.sum(); }
    public long filteredCount() { return filtered.sum(); }

    public final class Scope {
        private final AgentObservationEvent.Identity identity;
        private final AgentObservationEvent.Kind kind;
        private final String name;
        private final long started = System.nanoTime();
        private final AtomicBoolean ended = new AtomicBoolean();

        private Scope(AgentObservationEvent.Identity identity, AgentObservationEvent.Kind kind, String name) {
            this.identity = identity; this.kind = kind; this.name = name;
        }

        public String id() { return identity.observationId(); }
        private AgentObservations owner() { return AgentObservations.this; }

        /** Explicitly enter on the execution thread; scopes do not depend on executor ThreadLocal inheritance. */
        public <T> T within(Supplier<T> action) {
            Scope previous = CURRENT.get();
            CURRENT.set(this);
            try { return action.get(); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }

        public <T> T withinCall(java.util.concurrent.Callable<T> action) throws Exception {
            Scope previous = CURRENT.get();
            CURRENT.set(this);
            try { return action.call(); }
            finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
        }

        public void complete(String outcome, Map<String, Object> data) {
            if (ended.compareAndSet(false, true)) emit(AgentObservationEvent.Phase.COMPLETED, outcome, data);
        }

        public void fail(Throwable error) {
            if (ended.compareAndSet(false, true)) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("errorType", error.getClass().getName());
                data.put("errorMessage", error.getMessage());
                emit(error instanceof AgentCancellationException
                        ? AgentObservationEvent.Phase.CANCELLED : AgentObservationEvent.Phase.FAILED,
                        "FAILED", data);
            }
        }

        public void event(String outcome, Map<String, Object> data) {
            emit(AgentObservationEvent.Phase.EVENT, outcome, data);
        }

        private void emit(AgentObservationEvent.Phase phase, String outcome, Map<String, Object> data) {
            if (observer == AgentExecutionObserver.NOOP) return;
            try {
                var event = new AgentObservationEvent(1, UUID.randomUUID().toString(), identity,
                        kind, phase, name, Instant.now(), Math.max(0, System.nanoTime() - started),
                        outcome, policy.snapshot(data));
                var processed = processor.process(event);
                if (processed == null) { filtered.increment(); return; }
                // Processing may redact data, but cannot corrupt the execution identity or lifecycle.
                observer.onEvent(processor == AgentObservationProcessor.IDENTITY ? event
                        : new AgentObservationEvent(event.schemaVersion(), event.eventId(), event.identity(),
                                event.kind(), event.phase(), event.name(), event.occurredAt(), event.durationNanos(),
                                event.outcome(), policy.snapshot(processed.data())));
            } catch (RuntimeException ignored) { captureFailures.increment(); }
        }
    }

    public static String currentParent(String fallback) {
        Scope current = CURRENT.get();
        return current == null ? fallback : current.id();
    }

    public static AgentObservations current() {
        Scope current = CURRENT.get();
        return current == null ? NOOP : current.owner();
    }

    public static void reportCurrent(String outcome, Map<String, Object> data) {
        Scope current = CURRENT.get();
        if (current != null) current.event(outcome, data);
    }
}
