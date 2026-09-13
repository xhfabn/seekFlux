package io.seekflux.platform.agentruntime.domain.service.loop;

import io.seekflux.platform.agentruntime.domain.model.run.AgentRunResult;
import io.seekflux.platform.agentruntime.domain.service.runtime.AgentRuntime;
import io.seekflux.platform.agentruntime.application.spi.business.context.ContextEngine;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.domain.model.execution.CancellationToken;
import io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSession;
import java.time.Clock;

public final class DefaultAgentLoop implements AgentLoop {

    private final AgentRuntime finiteStepRuntime;
    private final ContextEngine contextEngine;
    private final Clock clock;

    public DefaultAgentLoop(AgentRuntime finiteStepRuntime, ContextEngine contextEngine, Clock clock) {
        this.finiteStepRuntime = finiteStepRuntime;
        this.contextEngine = contextEngine;
        this.clock = clock;
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
        AgentRunResult result = finiteStepRuntime.run(
                context.definition(),
                context.request(),
                decisionContext -> {
                    cancellationToken.throwIfCancelled();
                    var call = context.llmClient().chatWithUsage(
                            contextEngine.assemble(session, context, decisionContext),
                            cancellationToken);
                    decisionContext.recordUsage(call.usage());
                    return call.decision();
                },
                cancellationToken);
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
}
