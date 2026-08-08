package io.seekflux.platform.agentruntime.router;

import io.seekflux.platform.agentruntime.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.execution.ExecutionAuthority;
import io.seekflux.platform.agentruntime.execution.SessionExecutor;
import io.seekflux.platform.agentruntime.feature.FeatureContext;
import io.seekflux.platform.agentruntime.feature.FeaturePipeline;
import io.seekflux.platform.agentruntime.feature.FeatureRequest;
import io.seekflux.platform.agentruntime.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.session.IngressCommitResult;
import java.time.Clock;
import java.util.Optional;

public final class DefaultRouter implements Router {

    private final FeaturePipeline featurePipeline;
    private final AgentSessionStore sessions;
    private final SessionExecutor sessionExecutor;
    private final Clock clock;

    public DefaultRouter(
            FeaturePipeline featurePipeline,
            AgentSessionStore sessions,
            SessionExecutor sessionExecutor,
            Clock clock) {
        this.featurePipeline = featurePipeline;
        this.sessions = sessions;
        this.sessionExecutor = sessionExecutor;
        this.clock = clock;
    }

    @Override
    public RouterResult execute(FeatureRequest request, PushEventPublisher publisher) {
        FeatureContext context = featurePipeline.process(request);
        String sessionId = request.runRequest().sessionId();

        // Position guard: authority is acquired before the user message is committed.
        Optional<ExecutionAuthority> acquired = sessionExecutor.tryAcquireExecution(sessionId);
        if (acquired.isEmpty()) {
            return RouterResult.busy();
        }
        ExecutionAuthority authority = acquired.get();
        try {
            IngressCommitResult commit = sessions.commitIngress(request.runRequest(), clock.instant());
            if (commit == IngressCommitResult.DUPLICATE) {
                authority.close();
                return RouterResult.duplicate();
            }
            return RouterResult.completed(sessionExecutor.run(
                    sessionId,
                    context.runtimeContext(),
                    publisher,
                    authority));
        } catch (RuntimeException error) {
            authority.close();
            throw error;
        }
    }

    @Override
    public boolean cancel(String sessionId, boolean steer) {
        return sessionExecutor.cancel(sessionId, steer);
    }
}
