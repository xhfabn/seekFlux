package io.seekflux.platform.agentruntime.execution;

import java.time.Instant;

public interface CancellationSignalStore {

    CancellationSignalStore NOOP = new CancellationSignalStore() {
        @Override
        public CancelSignal poll(String sessionId, Instant taskStartedAt) {
            return CancelSignal.NONE;
        }

        @Override
        public boolean write(String sessionId, boolean steer, Instant signalTime) {
            return false;
        }
    };

    CancelSignal poll(String sessionId, Instant taskStartedAt);

    boolean write(String sessionId, boolean steer, Instant signalTime);

    record CancelSignal(boolean cancelled, boolean steer) {
        public static final CancelSignal NONE = new CancelSignal(false, false);
    }
}
