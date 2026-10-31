package io.seekflux.platform.agentruntime.domain.service.observation;

import io.seekflux.platform.agentruntime.application.spi.business.context.ContextEngine;
import io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext;
import io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext;
import io.seekflux.platform.agentruntime.application.spi.capability.observation.AgentObservationEvent.Kind;
import io.seekflux.platform.agentruntime.domain.model.context.ContextAssemblyMode;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import java.util.*;
import java.util.function.Supplier;

public final class ObservingContextEngine implements ContextEngine {
    private final ContextEngine delegate;
    private final AgentObservations observations;
    public ObservingContextEngine(ContextEngine delegate, AgentObservations observations) {
        this.delegate = Objects.requireNonNull(delegate);
        this.observations = Objects.requireNonNull(observations);
    }
    @Override public int overflowRetryLimit() { return delegate.overflowRetryLimit(); }
    @Override public AssembledContext assemble(AgentSession session, RuntimeContext runtime,
            AgentDecisionContext context) {
        return call(context, () -> delegate.assemble(session, runtime, context));
    }
    @Override public AssembledContext assemble(AgentSession session, RuntimeContext runtime,
            AgentDecisionContext context, ContextAssemblyMode mode) {
        return call(context, () -> delegate.assemble(session, runtime, context, mode));
    }
    private AssembledContext call(AgentDecisionContext context, Supplier<AssembledContext> action) {
        var span = observations.start(context.request(), context.agentRunId(), UUID.randomUUID().toString(),
                AgentObservations.currentParent(AgentObservations.planId(context.agentRunId(), context.step())),
                null, Kind.CONTEXT, "context.assemble", Map.of("step", context.step()));
        try {
            AssembledContext result = span.within(action);
            span.complete(result == null ? "NULL_RESULT" : "ASSEMBLED", result == null ? Map.of() :
                    Map.of("estimatedTokens", result.estimatedTokens(), "budgetTokens", result.budgetTokens(),
                            "compacted", result.compacted(), "compactionCutoff", result.compactionCutoff(),
                            "assemblyMode", result.assemblyMode(), "output", result.messages()));
            return result;
        } catch (RuntimeException | Error error) { span.fail(error); throw error; }
    }
}
