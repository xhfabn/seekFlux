package io.seekflux.platform.agentruntime.llm;

import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.context.AssembledContext;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public final class ShadowingLlmClient implements LlmClient {

    private final LlmClient primary;
    private final LlmClient shadow;
    private final String shadowVersion;
    private final ShadowControl control;
    private final ExecutorService executor;
    private final AgentShadowRecorder recorder;
    private final Clock clock;

    public ShadowingLlmClient(
            LlmClient primary,
            LlmClient shadow,
            String shadowVersion,
            ShadowControl control,
            ExecutorService executor,
            AgentShadowRecorder recorder,
            Clock clock) {
        this.primary = primary;
        this.shadow = shadow;
        this.shadowVersion = shadowVersion;
        this.control = control;
        this.executor = executor;
        this.recorder = recorder;
        this.clock = clock;
    }

    @Override
    public String version() {
        return primary.version();
    }

    @Override
    public AgentDecision chat(AssembledContext context) {
        return chatWithUsage(context).decision();
    }

    @Override
    public LlmCallResult chatWithUsage(AssembledContext context) {
        LlmCallResult primaryResult = primary.chatWithUsage(context);
        String requestId = context.decisionContext().request().requestId();
        if (control.shouldSample(requestId)) {
            try {
                executor.submit(() -> evaluate(context, primaryResult.decision()));
            } catch (RejectedExecutionException ignored) {
                // Shadow saturation must never affect the primary result.
            }
        }
        return primaryResult;
    }

    private void evaluate(AssembledContext context, AgentDecision primaryDecision) {
        long started = System.nanoTime();
        String shadowDecision = null;
        String errorCode = null;
        boolean agreed = false;
        try {
            AgentDecision candidate = shadow.chatWithUsage(context).decision();
            shadowDecision = decisionType(candidate);
            agreed = decisionFingerprint(primaryDecision).equals(decisionFingerprint(candidate));
        } catch (RuntimeException failure) {
            errorCode = failure.getClass().getSimpleName();
        }
        var decisionContext = context.decisionContext();
        recorder.record(new ShadowEvaluation(
                UUID.randomUUID(),
                decisionContext.request().requestId(),
                decisionContext.request().sessionId(),
                decisionContext.step(),
                primary.version(),
                shadowVersion,
                decisionType(primaryDecision),
                shadowDecision,
                agreed,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started),
                errorCode,
                clock.instant()));
    }

    private static String decisionType(AgentDecision decision) {
        return decision.getClass().getSimpleName();
    }

    private static String decisionFingerprint(AgentDecision decision) {
        return decisionType(decision) + ":" + decision;
    }
}
