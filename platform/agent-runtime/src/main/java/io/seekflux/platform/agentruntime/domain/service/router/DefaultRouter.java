package io.seekflux.platform.agentruntime.domain.service.router;

import io.seekflux.platform.agentruntime.application.api.Router;
import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.spi.capability.execution.ExecutionAuthority;
import io.seekflux.platform.agentruntime.domain.service.execution.SessionExecutor;
import io.seekflux.platform.agentruntime.domain.model.feature.FeatureContext;
import io.seekflux.platform.agentruntime.domain.service.feature.FeaturePipeline;
import io.seekflux.platform.agentruntime.application.command.FeatureRequest;
import io.seekflux.platform.agentruntime.application.spi.capability.session.AgentSessionStore;
import io.seekflux.platform.agentruntime.domain.model.session.IngressCommitResult;
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
            IngressCommitResult commit = sessions.commitIngress(
                    request.runRequest(), authority.fencingToken(), clock.instant());
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
