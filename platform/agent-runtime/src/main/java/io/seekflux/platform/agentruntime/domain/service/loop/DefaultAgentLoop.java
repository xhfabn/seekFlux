package io.seekflux.platform.agentruntime.domain.service.loop;

import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.service.runtime.AgentRuntime;
import io.seekflux.platform.agentruntime.application.spi.business.context.ContextEngine;
import io.seekflux.platform.agentruntime.application.spi.capability.context.ContextEventRecorder;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.exception.ContextOverflowException;
import io.seekflux.platform.agentruntime.domain.model.context.ContextAssemblyMode;
import io.seekflux.platform.agentruntime.domain.model.context.ContextEvent;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import io.seekflux.platform.agentruntime.domain.service.recovery.AgentRecoveryExecution;
import java.time.Clock;

public final class DefaultAgentLoop implements AgentLoop {

    private final AgentRuntime finiteStepRuntime;
    private final ContextEngine contextEngine;
    private final Clock clock;
    private final ContextEventRecorder contextEvents;

    public DefaultAgentLoop(AgentRuntime finiteStepRuntime, ContextEngine contextEngine, Clock clock) {
        this(finiteStepRuntime, contextEngine, clock, ContextEventRecorder.NOOP);
    }

    public DefaultAgentLoop(
            AgentRuntime finiteStepRuntime,
            ContextEngine contextEngine,
            Clock clock,
            ContextEventRecorder contextEvents) {
        this.finiteStepRuntime = finiteStepRuntime;
        this.contextEngine = contextEngine;
        this.clock = clock;
        this.contextEvents = contextEvents == null ? ContextEventRecorder.NOOP : contextEvents;
    }

    @Override
    public String loopType() {
        return "default";
    }

    @Override
    public AgentRunResult run(
            AgentSession session,
            RuntimeContext context,
            PushEventPublisher publisher,
            CancellationToken cancellationToken) {
        return run(session, context, publisher, cancellationToken, AgentRecoveryExecution.DISABLED);
    }

    @Override
    public AgentRunResult run(
            AgentSession session,
            RuntimeContext context,
            PushEventPublisher publisher,
            CancellationToken cancellationToken,
            AgentRecoveryExecution recovery) {
        AgentRunResult result = finiteStepRuntime.run(
                context.definition(),
                context.request(),
                decisionContext -> {
                    cancellationToken.throwIfCancelled();
                    var call = callModel(
                            session, context, decisionContext, cancellationToken);
                    decisionContext.recordUsage(call.usage());
                    decisionContext.recordAssistantContent(call.assistantContent());
                    return call.decision();
                },
                cancellationToken,
                recovery);
        publisher.publish(new PushEvent.LoopStarted(
                result.trace().agentRunId(),
                result.trace().startedAt(),
                context.definition().id()));
        result.trace().steps().stream()
                .filter(step -> "CALL_TOOL".equals(step.action()))
                .forEach(step -> publisher.publish(new PushEvent.ToolCompleted(
                        result.trace().agentRunId(),
                        clock.instant(),
                        step.toolCallId(),
                        step.toolName(),
                        step.status(),
                        step.linkedTraceId())));
        publisher.publish(new PushEvent.LoopCompleted(
                result.trace().agentRunId(),
                clock.instant(),
                result.state(),
                result.trace().tookMillis(),
                result.cancellationReason()));
        return result;
    }

    private io.seekflux.platform.agentruntime.application.spi.capability.llm.model.LlmCallResult callModel(
            AgentSession session,
            RuntimeContext runtimeContext,
            io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext decision,
            CancellationToken cancellationToken) {
        var assembled = contextEngine.assemble(session, runtimeContext, decision);
        int attempt = 0;
        while (true) {
            cancellationToken.throwIfCancelled();
            try {
                return runtimeContext.llmClient().chatWithUsage(assembled, cancellationToken);
            } catch (ContextOverflowException overflow) {
                if (attempt >= contextEngine.overflowRetryLimit()) {
                    recordContextEvent(
                            ContextEvent.Type.OVERFLOW_EXHAUSTED,
                            decision,
                            assembled,
                            "HTTP_" + overflow.statusCode());
                    throw overflow;
                }
                attempt++;
                recordContextEvent(
                        ContextEvent.Type.OVERFLOW_RETRY,
                        decision,
                        assembled,
                        "HTTP_" + overflow.statusCode());
                assembled = contextEngine.assemble(
                        session,
                        runtimeContext,
                        decision,
                        ContextAssemblyMode.OVERFLOW_FALLBACK);
            }
        }
    }

    private void recordContextEvent(
            ContextEvent.Type type,
            io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext decision,
            io.seekflux.platform.agentruntime.application.spi.capability.llm.model.AssembledContext context,
            String reason) {
        contextEvents.record(new ContextEvent(
                type,
                decision.request().sessionId(),
                decision.request().requestId(),
                context.estimatedTokens(),
                context.budgetTokens(),
                reason,
                clock.instant()));
    }
}
