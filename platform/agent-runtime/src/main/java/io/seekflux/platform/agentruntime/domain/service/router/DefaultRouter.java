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
import io.seekflux.platform.agentruntime.domain.model.session.QueueCommitResult;
import io.seekflux.platform.agentruntime.application.command.AgentIngressMode;
import io.seekflux.platform.agentruntime.domain.model.session.AgentSessionStatus;
import java.time.Clock;
import java.util.Optional;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResumeResult;

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
        if (context.session().pendingWait().isPresent()
                && request.runRequest().ingressMode() != AgentIngressMode.QUEUE) {
            return RouterResult.rejected("SESSION_WAITING_REQUIRES_INTERNAL_RESUME", 0);
        }
        if (request.runRequest().ingressMode() == AgentIngressMode.QUEUE
                && context.session().status() != AgentSessionStatus.SUSPENDED) {
            return RouterResult.rejected("SESSION_NOT_WAITING", 0);
        }

        // Position guard: authority is acquired before the user message is committed.
        Optional<ExecutionAuthority> acquired = sessionExecutor.tryAcquireExecution(sessionId);
        if (acquired.isEmpty()) {
            if (request.runRequest().ingressMode() != AgentIngressMode.NEW_EXECUTION) {
                return enqueue(
                        context.runtimeContext(),
                        publisher,
                        request.runRequest().ingressMode() == AgentIngressMode.STEER);
            }
            return RouterResult.busy();
        }
        ExecutionAuthority authority = acquired.get();
        try {
            if (request.runRequest().ingressMode() == AgentIngressMode.QUEUE
                    || (request.runRequest().ingressMode() == AgentIngressMode.STEER
                    && context.session().status() == AgentSessionStatus.EXECUTING)) {
                authority.close();
                RouterResult queued = enqueue(
                        context.runtimeContext(),
                        publisher,
                        request.runRequest().ingressMode() == AgentIngressMode.STEER);
                return queued;
            }
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
                    authority,
                    commit));
        } catch (RuntimeException error) {
            authority.close();
            throw error;
        }
    }

    private RouterResult enqueue(
            io.seekflux.platform.agentruntime.domain.model.feature.RuntimeContext context,
            PushEventPublisher publisher,
            boolean steer) {
        QueueCommitResult committed = sessionExecutor.commitQueuedMessage(
                context, publisher, steer);
        if (committed.status() == QueueCommitResult.Status.FULL) {
            return RouterResult.rejected("STEER_QUEUE_FULL", committed.queueDepth());
        }
        if (committed.status() == QueueCommitResult.Status.DUPLICATE_CONSUMED) {
            return RouterResult.duplicate();
        }
        if (steer) {
            sessionExecutor.drainPendingIfIdle(
                    context.request().sessionId(), context, publisher);
        }
        return RouterResult.queued(
                committed.queueDepth(),
                committed.status() == QueueCommitResult.Status.DUPLICATE_PENDING);
    }

    @Override
    public boolean cancel(String sessionId, boolean steer) {
        return sessionExecutor.cancel(sessionId, steer);
    }

    @Override
    public RouterResult resume(
            WaitResolution resolution,
            FeatureRequest request,
            PushEventPublisher publisher) {
        if (!resolution.sessionId().equals(request.runRequest().sessionId())
                || !resolution.requestId().equals(request.runRequest().requestId())
                || !resolution.turnId().equals(request.runRequest().turnId())) {
            return RouterResult.rejected("WAIT_RESUME_IDENTITY_MISMATCH", 0);
        }
        FeatureContext context = featurePipeline.process(request);
        WaitResumeResult resumed = sessionExecutor.resumeWait(
                resolution, context.runtimeContext(), publisher);
        return switch (resumed.status()) {
            case COMPLETED -> RouterResult.completed(resumed.outcome());
            case BUSY -> RouterResult.busy();
            case DUPLICATE -> RouterResult.duplicate();
            case CONFLICT -> RouterResult.rejected("WAIT_RESOLUTION_CONFLICT", 0);
            case MISSING -> RouterResult.rejected("WAIT_NOT_FOUND", 0);
        };
    }
}
