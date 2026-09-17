package io.seekflux.apps.agentserver.interfaces.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.seekflux.agent.port.in.AgentSearchUseCase;
import io.seekflux.platform.agentruntime.application.api.Router;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventPublisher;
import io.seekflux.platform.agentruntime.application.spi.capability.event.PushEventStream;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushFrame;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AgentSearchControllerStreamTest {

    @Test
    void reconnectWithLastEventIdNeverStartsADuplicateExecution() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        AgentSearchUseCase useCase = command -> {
            executions.incrementAndGet();
            throw new AssertionError("reconnect must not execute the command");
        };
        var executor = Executors.newSingleThreadExecutor();
        try {
            AgentSearchController controller = new AgentSearchController(
                    useCase, mock(Router.class), new TerminalStream(), executor, 1_000);
            AgentSearchRequest request = new AgentSearchRequest(
                    "request-1", "session-1", "turn-1", "search-assistant", "露营",
                    0, 12, List.of(), null, null, null, null);

            controller.stream(request, "7");
            executor.shutdown();
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
            assertThat(executions.get()).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    private static final class TerminalStream implements PushEventStream {
        @Override
        public PushEventPublisher publisher(String sessionId, String requestId) {
            return PushEventPublisher.NOOP;
        }

        @Override
        public Subscription subscribe(String sessionId, long afterSequence) {
            return new Subscription() {
                @Override
                public PushFrame poll(Duration timeout) {
                    return null;
                }

                @Override
                public boolean replayGap() {
                    return false;
                }

                @Override
                public boolean overflowed() {
                    return false;
                }

                @Override
                public boolean terminalObserved() {
                    return true;
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
