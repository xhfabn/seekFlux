package io.seekflux.platform.agentruntime.domain.service.wait;

import io.seekflux.platform.agentruntime.application.api.WaitResumeDispatcher;
import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.business.agent.DelegatedAgentLauncher;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.domain.model.tool.AgentToolResult;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitRequest;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import java.time.Clock;
import java.util.Map;

/** Parent/child and handoff lifecycle with explicit depth, budget and identity propagation. */
public final class ParentChildAgentCoordinator {

    private final DelegatedAgentLauncher launcher;
    private final WaitResumeDispatcher resumes;
    private final Clock clock;
    private final int maxDepth;

    public ParentChildAgentCoordinator(
            DelegatedAgentLauncher launcher,
            WaitResumeDispatcher resumes,
            Clock clock,
            int maxDepth) {
        if (maxDepth < 1) {
            throw new IllegalArgumentException("maximum child depth must be positive");
        }
        this.launcher = launcher;
        this.resumes = resumes;
        this.clock = clock;
        this.maxDepth = maxDepth;
    }

    public AgentToolResult launch(DelegatedAgentLauncher.Command command) {
        if (command.mode() == DelegatedAgentLauncher.Mode.FORK) {
            return AgentToolResult.failure("FORK_PROMOTION_UNSUPPORTED");
        }
        if (command.depth() > maxDepth) {
            return AgentToolResult.failure("CHILD_AGENT_DEPTH_EXCEEDED");
        }
        if (command.budgetMillis() > command.parentRemainingBudgetMillis()
                || command.budgetMillis() > command.timeout().toMillis()) {
            return AgentToolResult.failure("CHILD_AGENT_BUDGET_INVALID");
        }
        DelegatedAgentLauncher.LaunchResult launched;
        try {
            launched = launcher.launch(command);
        } catch (RuntimeException launchFailure) {
            return AgentToolResult.failure("DELEGATED_AGENT_LAUNCH_FAILED");
        }
        WaitRequest request = command.mode() == DelegatedAgentLauncher.Mode.HANDOFF
                ? new WaitRequest.Handoff(
                        command.childAgentId(), launched.childSessionId(), command.timeout())
                : new WaitRequest.ChildAgent(
                        command.childAgentId(),
                        launched.childSessionId(),
                        command.depth(),
                        command.budgetMillis(),
                        command.timeout());
        return AgentToolResult.waiting(request);
    }

    public RouterResult completeChild(
            WaitState.ChildAgent wait,
            String resolutionId,
            Map<String, Object> output) {
        return resumes.dispatch(
                resolution(wait, resolutionId, WaitResolution.Outcome.COMPLETED, output, null),
                PushEventPublisher.NOOP);
    }

    public RouterResult completeHandoff(
            WaitState.Handoff wait,
            String resolutionId,
            Map<String, Object> output) {
        return resumes.dispatch(
                resolution(wait, resolutionId, WaitResolution.Outcome.COMPLETED, output, null),
                PushEventPublisher.NOOP);
    }

    public RouterResult cancelChild(
            WaitState.ChildAgent wait,
            String resolutionId,
            String reason) {
        return cancelDelegated(wait, resolutionId, wait.childSessionId(), reason);
    }

    public RouterResult cancelHandoff(
            WaitState.Handoff wait,
            String resolutionId,
            String reason) {
        return cancelDelegated(wait, resolutionId, wait.targetSessionId(), reason);
    }

    private RouterResult cancelDelegated(
            WaitState wait,
            String resolutionId,
            String delegatedSessionId,
            String reason) {
        try {
            launcher.cancel(resolutionId, delegatedSessionId, reason);
        } catch (RuntimeException ignored) {
            // Parent cancellation still wins; a durable launcher must reconcile by operation id.
        }
        return resumes.dispatch(
                resolution(
                        wait,
                        resolutionId,
                        WaitResolution.Outcome.CANCELLED,
                        Map.of(),
                        reason == null || reason.isBlank() ? "USER_CANCEL" : reason),
                PushEventPublisher.NOOP);
    }

    private WaitResolution resolution(
            WaitState wait,
            String resolutionId,
            WaitResolution.Outcome outcome,
            Map<String, Object> output,
            String errorCode) {
        return new WaitResolution(
                1,
                resolutionId,
                wait.waitId(),
                wait.sessionId(),
                wait.requestId(),
                wait.turnId(),
                outcome,
                output,
                errorCode,
                "delegated-agent:" + wait.waitId(),
                clock.instant());
    }
}
