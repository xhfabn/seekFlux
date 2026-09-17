package io.seekflux.platform.agentruntime.application.spi.capability.event;

import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushFrame;
import java.time.Duration;

public interface PushEventStream {

    PushEventPublisher publisher(String sessionId, String requestId);

    Subscription subscribe(String sessionId, long afterSequence);

    interface Subscription extends AutoCloseable {

        PushFrame poll(Duration timeout) throws InterruptedException;

        boolean replayGap();

        boolean overflowed();

        boolean terminalObserved();

        @Override
        void close();
    }
}
