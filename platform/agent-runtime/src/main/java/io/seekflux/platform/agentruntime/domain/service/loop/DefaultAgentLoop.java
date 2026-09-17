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
        PushEventPublisher isolatedPublisher = isolated(publisher);
        AgentRunResult result = finiteStepRuntime.run(
                context.definition(),
                context.request(),
                decisionContext -> {
                    cancellationToken.throwIfCancelled();
                    var call = callModel(
                            session, context, decisionContext, cancellationToken, isolatedPublisher);
                    decisionContext.recordUsage(call.usage());
                    decisionContext.recordAssistantContent(call.assistantContent());
                    return call.decision();
                },
                cancellationToken,
                recovery,
                isolatedPublisher);
        result.trace().steps().stream()
                .filter(step -> "CALL_TOOL".equals(step.action()))
                .forEach(step -> isolatedPublisher.publish(new PushEvent.ToolCompleted(
                        result.trace().agentRunId(),
                        clock.instant(),
                        step.toolCallId(),
                        step.toolName(),
                        step.status(),
                        step.linkedTraceId())));
        if (result.state()
                == io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.CANCELLED) {
            isolatedPublisher.publish(new PushEvent.Control(
                    result.trace().agentRunId(), clock.instant(), "CANCELLED",
                    result.cancellationReason()));
        } else if (result.state()
                        == io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.FAILED
                || result.state()
                        == io.seekflux.platform.agentruntime.domain.model.run.AgentTerminalState.FALLBACK_REQUIRED) {
            isolatedPublisher.publish(new PushEvent.RuntimeError(
                    result.trace().agentRunId(), clock.instant(), result.fallbackReason()));
        }
        isolatedPublisher.publish(new PushEvent.LoopCompleted(
                result.trace().agentRunId(),
                clock.instant(),
                result.state(),
                result.trace().tookMillis(),
                result.cancellationReason()));
        return result;
    }

    private static PushEventPublisher isolated(PushEventPublisher publisher) {
        PushEventPublisher delegate = publisher == null ? PushEventPublisher.NOOP : publisher;
        return event -> {
            try {
                return delegate.publish(event);
            } catch (RuntimeException ignored) {
                return -1;
            }
        };
    }

    private io.seekflux.platform.agentruntime.application.spi.capability.llm.model.LlmCallResult callModel(
            AgentSession session,
            RuntimeContext runtimeContext,
            io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext decision,
            CancellationToken cancellationToken,
            PushEventPublisher publisher) {
        var assembled = contextEngine.assemble(session, runtimeContext, decision);
        int attempt = 0;
        while (true) {
            cancellationToken.throwIfCancelled();
            try {
                publisher.publish(new PushEvent.LlmTurnStarted(
                        decision.agentRunId(), clock.instant(), decision.step(),
                        runtimeContext.llmClient().version()));
                var result = runtimeContext.llmClient().streamWithUsage(
                        assembled,
                        cancellationToken,
                        chunk -> publishChunk(publisher, decision, chunk));
                publisher.publish(new PushEvent.LlmTurnCompleted(
                        decision.agentRunId(), clock.instant(), decision.step(), "completed",
                        result.usage().inputTokens(), result.usage().outputTokens(),
                        result.usage().cachedInputTokens(), result.usage().reasoningTokens()));
                return result;
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

    private void publishChunk(
            PushEventPublisher publisher,
            io.seekflux.platform.agentruntime.application.spi.business.planner.model.AgentDecisionContext decision,
            io.seekflux.platform.agentruntime.application.spi.capability.llm.model.ChatChunk chunk) {
        if (!chunk.contentDelta().isEmpty()) {
            publisher.publish(new PushEvent.ContentDelta(
                    decision.agentRunId(), clock.instant(), decision.step(),
                    chunk.sequence(), chunk.contentDelta()));
        }
        if (!chunk.reasoningDelta().isEmpty()) {
            publisher.publish(new PushEvent.ReasoningDelta(
                    decision.agentRunId(), clock.instant(), decision.step(),
                    chunk.sequence(), chunk.reasoningDelta()));
        }
        if (chunk.toolCallDelta() != null) {
            var tool = chunk.toolCallDelta();
            publisher.publish(new PushEvent.ToolCallDelta(
                    decision.agentRunId(), clock.instant(), decision.step(), chunk.sequence(),
                    tool.index(), tool.idDelta(), tool.nameDelta(), tool.argumentsDelta(),
                    tool.argumentsComplete()));
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
