package io.seekflux.platform.agentruntime.loop;

import io.seekflux.platform.agentruntime.AgentDecision;
import io.seekflux.platform.agentruntime.AgentRunResult;
import io.seekflux.platform.agentruntime.AgentRuntime;
import io.seekflux.platform.agentruntime.context.ContextEngine;
import io.seekflux.platform.agentruntime.event.PushEvent;
import io.seekflux.platform.agentruntime.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.execution.CancellationToken;
import io.seekflux.platform.agentruntime.feature.RuntimeContext;
import io.seekflux.platform.agentruntime.session.AgentSession;
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
        if (cancellationToken.isCancelled()) {
            return finiteStepRuntime.run(
                    context.definition(),
                    context.request(),
                    ignored -> new AgentDecision.Fallback("AGENT_CANCELLED"));
        }
        AgentRunResult result = finiteStepRuntime.run(
                context.definition(),
                context.request(),
                decisionContext -> {
                    if (cancellationToken.isCancelled()) {
                        return new AgentDecision.Fallback("AGENT_CANCELLED");
                    }
                    return context.llmClient().chat(
                            contextEngine.assemble(session, context, decisionContext));
                });
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
                result.trace().tookMillis()));
        return result;
    }
}
