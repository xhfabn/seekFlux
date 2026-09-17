package io.seekflux.platform.agentruntime.application.spi.capability.event;

import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushFrame;
import io.seekflux.platform.agentruntime.application.spi.capability.event.model.PushEvent;
import java.time.Instant;
import java.util.function.Consumer;

public interface PushEventRelay {

    PushEventRelay LOCAL_ONLY = new PushEventRelay() {
        @Override
        public long nextSequence(String sessionId) {
            return -1;
        }

        @Override
        public void broadcast(PushFrame frame) {
        }

        @Override
        public AutoCloseable listen(Consumer<PushFrame> listener) {
            return () -> { };
        }
    };

    long nextSequence(String sessionId);

    void broadcast(PushFrame frame);

    default PushFrame publish(
            String sessionId,
            String requestId,
            String sourceId,
            PushEvent event,
            Instant publishedAt,
            long minimumSequence) {
        long sequence = nextSequence(sessionId);
        if (sequence < 0) {
            return null;
        }
        PushFrame sequenced = new PushFrame(
                sessionId, requestId, sequence, sourceId, event, publishedAt);
        broadcast(sequenced);
        return sequenced;
    }

    AutoCloseable listen(Consumer<PushFrame> listener);
}
