package io.seekflux.platform.agentruntime.domain.service.observation;

import io.seekflux.platform.agentruntime.application.spi.capability.llm.LlmClient;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.*;
import io.seekflux.platform.agentruntime.application.spi.capability.observation.AgentObservationEvent.Kind;
import io.seekflux.platform.agentruntime.domain.model.decision.AgentDecision;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.run.LlmUsage;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.*;

/** Observes logical client invocations, not provider-internal HTTP retries. */
public final class ObservingLlmClient implements LlmClient {
    private final LlmClient delegate;
    private final AgentObservations observations;
    private final String name;
    public ObservingLlmClient(LlmClient delegate, AgentObservations observations) {
        this(delegate, observations, "llm");
    }
    public ObservingLlmClient(LlmClient delegate, AgentObservations observations, String name) {
        this.delegate = Objects.requireNonNull(delegate);
        this.observations = Objects.requireNonNull(observations);
        this.name = Objects.requireNonNull(name);
    }
    @Override public String version() { return delegate.version(); }
    @Override public AgentDecision chat(AssembledContext context) {
        return call(context, () -> new LlmCallResult(delegate.chat(context), LlmUsage.UNMEASURED), null).decision();
    }
    @Override public AgentDecision chat(AssembledContext context, CancellationToken token) {
        return call(context, () -> new LlmCallResult(delegate.chat(context, token), LlmUsage.UNMEASURED), null).decision();
    }
    @Override public LlmCallResult chatWithUsage(AssembledContext context) {
        return call(context, () -> delegate.chatWithUsage(context), null);
    }
    @Override public LlmCallResult chatWithUsage(AssembledContext context, CancellationToken token) {
        return call(context, () -> delegate.chatWithUsage(context, token), null);
    }
    @Override public LlmCallResult streamWithUsage(AssembledContext context, CancellationToken token,
            Consumer<ChatChunk> chunks) {
        StreamMetrics metrics = new StreamMetrics();
        return call(context, () -> delegate.streamWithUsage(context, token, chunk -> {
            metrics.count.incrementAndGet();
            if (chunk.hasOutput()) metrics.firstOutputNanos.compareAndSet(-1, System.nanoTime() - metrics.started);
            if (chunk.usage().measured()) metrics.usage.set(chunk.usage());
            chunks.accept(chunk);
        }), metrics);
    }
    private LlmCallResult call(AssembledContext context, Supplier<LlmCallResult> action, StreamMetrics chunks) {
        var decision = context.decisionContext();
        String runId = decision.agentRunId();
        var span = observations.start(decision.request(), runId, UUID.randomUUID().toString(),
                AgentObservations.currentParent(AgentObservations.planId(runId, decision.step())),
                null, Kind.MODEL, name,
                Map.of("providerVersion", version(), "specId", context.specId() == null ? "" : context.specId(),
                        "estimatedTokens", context.estimatedTokens(), "budgetTokens", context.budgetTokens(),
                        "input", Map.of("messages", context.messages(), "tools", context.tools()),
                        "streaming", chunks != null));
        try {
            LlmCallResult result = span.within(action);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("output", result);
            data.put("usage", result == null ? LlmUsage.UNMEASURED : result.usage());
            if (chunks != null) data.putAll(chunks.snapshot());
            span.complete(result == null ? "NULL_RESULT" : "RETURNED", data);
            return result;
        } catch (RuntimeException | Error error) {
            if (chunks != null) span.event("STREAM_INTERRUPTED", chunks.snapshot());
            span.fail(error);
            throw error;
        }
    }
    private static final class StreamMetrics {
        final long started = System.nanoTime();
        final AtomicLong count = new AtomicLong();
        final AtomicLong firstOutputNanos = new AtomicLong(-1);
        final AtomicReference<LlmUsage> usage = new AtomicReference<>(LlmUsage.UNMEASURED);
        Map<String, Object> snapshot() {
            return Map.of("chunkCount", count.get(), "firstOutputNanos", firstOutputNanos.get(),
                    "lastStreamUsage", usage.get());
        }
    }

}
