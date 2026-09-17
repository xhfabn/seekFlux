package io.seekflux.platform.agentruntime.domain.service.wait;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seekflux.platform.agentruntime.application.api.model.RouterResult;
import io.seekflux.platform.agentruntime.application.spi.business.agent.DelegatedAgentLauncher;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitRequest;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitResolution;
import io.seekflux.platform.agentruntime.domain.model.wait.WaitState;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ParentChildAgentCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");

    @Test
    void propagatesIdentityDepthBudgetAndReturnsTypedChildWait() {
        List<DelegatedAgentLauncher.Command> launched = new ArrayList<>();
        ParentChildAgentCoordinator coordinator = coordinator(launched, new ArrayList<>(),
                new AtomicInteger());
        DelegatedAgentLauncher.Command command = command(2, 1000, 800);

        var result = coordinator.launch(command);

        assertTrue(result.waiting());
        WaitRequest.ChildAgent wait = (WaitRequest.ChildAgent) result.waitRequest();
        assertEquals("child-session", wait.childSessionId());
        assertEquals(2, wait.depth());
        assertEquals(800, wait.remainingBudgetMillis());
        assertEquals("user-1", launched.getFirst().identity().get("userId"));
    }

    @Test
    void rejectsDepthAndBudgetBeforeLaunchingChild() {
        List<DelegatedAgentLauncher.Command> launched = new ArrayList<>();
        ParentChildAgentCoordinator coordinator = coordinator(launched, new ArrayList<>(),
                new AtomicInteger());

        assertEquals(
                "CHILD_AGENT_DEPTH_EXCEEDED",
                coordinator.launch(command(4, 1000, 500)).errorCode());
        assertEquals(
                "CHILD_AGENT_BUDGET_INVALID",
                coordinator.launch(command(2, 500, 800)).errorCode());
        assertTrue(launched.isEmpty());
    }

    @Test
    void rejectsUnimplementedForkAndIsolatesLauncherFailure() {
        List<DelegatedAgentLauncher.Command> launched = new ArrayList<>();
        ParentChildAgentCoordinator coordinator = coordinator(
                launched, new ArrayList<>(), new AtomicInteger());
        DelegatedAgentLauncher.Command child = command(2, 1000, 800);
        DelegatedAgentLauncher.Command fork = new DelegatedAgentLauncher.Command(
                child.operationId(),
                DelegatedAgentLauncher.Mode.FORK,
                child.parentSessionId(),
                child.parentRequestId(),
                child.parentTurnId(),
                child.childAgentId(),
                child.requestedChildSessionId(),
                child.input(),
                child.identity(),
                child.depth(),
                child.parentRemainingBudgetMillis(),
                child.budgetMillis(),
                child.timeout());

        assertEquals("FORK_PROMOTION_UNSUPPORTED", coordinator.launch(fork).errorCode());
        assertTrue(launched.isEmpty());

        ParentChildAgentCoordinator failing = new ParentChildAgentCoordinator(
                new DelegatedAgentLauncher() {
                    @Override public LaunchResult launch(Command command) {
                        throw new IllegalStateException("offline");
                    }
                    @Override public boolean cancel(
                            String operationId, String childSessionId, String reason) {
                        return false;
                    }
                },
                (resolution, publisher) -> RouterResult.completed(null),
                Clock.fixed(NOW, ZoneOffset.UTC),
                3);
        assertEquals(
                "DELEGATED_AGENT_LAUNCH_FAILED",
                failing.launch(child).errorCode());
    }

    @Test
    void completionAndCancellationUseTheSameParentWaitResolutionProtocol() {
        List<WaitResolution> resolutions = new ArrayList<>();
        AtomicInteger cancellations = new AtomicInteger();
        ParentChildAgentCoordinator coordinator = coordinator(
                new ArrayList<>(), resolutions, cancellations);
        WaitState.ChildAgent wait = new WaitState.ChildAgent(
                1, "wait", "parent", "request", "turn", "checkpoint", "call",
                NOW, NOW.plusSeconds(10), "child", "child-session", 2, 800);

        assertEquals(
                RouterResult.Status.COMPLETED,
                coordinator.completeChild(wait, "child-complete", Map.of("answer", "ok")).status());
        assertEquals(WaitResolution.Outcome.COMPLETED, resolutions.getFirst().outcome());
        assertEquals(
                RouterResult.Status.COMPLETED,
                coordinator.cancelChild(wait, "parent-cancel", "USER_CANCEL").status());
        assertEquals(WaitResolution.Outcome.CANCELLED, resolutions.getLast().outcome());
        assertEquals(1, cancellations.get());
    }

    private static ParentChildAgentCoordinator coordinator(
            List<DelegatedAgentLauncher.Command> launched,
            List<WaitResolution> resolutions,
            AtomicInteger cancellations) {
        DelegatedAgentLauncher launcher = new DelegatedAgentLauncher() {
            @Override
            public LaunchResult launch(Command command) {
                launched.add(command);
                return new LaunchResult("child-session", false);
            }

            @Override
            public boolean cancel(String operationId, String childSessionId, String reason) {
                cancellations.incrementAndGet();
                return true;
            }
        };
        return new ParentChildAgentCoordinator(
                launcher,
                (resolution, publisher) -> {
                    resolutions.add(resolution);
                    return RouterResult.completed(null);
                },
                Clock.fixed(NOW, ZoneOffset.UTC),
                3);
    }

    private static DelegatedAgentLauncher.Command command(
            int depth, long parentBudget, long childBudget) {
        return new DelegatedAgentLauncher.Command(
                "operation",
                DelegatedAgentLauncher.Mode.CHILD_AGENT,
                "parent",
                "request",
                "turn",
                "child",
                "child-session",
                "do work",
                Map.of("userId", "user-1", "tenantId", "tenant-1"),
                depth,
                parentBudget,
                childBudget,
                Duration.ofSeconds(2));
    }
}
