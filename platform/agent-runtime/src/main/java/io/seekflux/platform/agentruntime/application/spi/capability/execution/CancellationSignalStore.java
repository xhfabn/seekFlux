package io.seekflux.platform.agentruntime.application.spi.capability.execution;

import io.seekflux.platform.agentruntime.domain.model.execution.CancellationCause;
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

    default boolean write(String sessionId, CancellationCause cause, Instant signalTime) {
        return write(sessionId, cause.isSteer(), signalTime);
    }

    record CancelSignal(boolean cancelled, CancellationCause cause) {
        public static final CancelSignal NONE = new CancelSignal(false, null);

        public CancelSignal {
            if (cancelled && cause == null) {
                cause = CancellationCause.USER_CANCEL;
            }
            if (!cancelled && cause != null) {
                throw new IllegalArgumentException("a non-cancelled signal cannot have a cause");
            }
        }

        public CancelSignal(boolean cancelled, boolean steer) {
            this(cancelled, cancelled
                    ? steer ? CancellationCause.STEER : CancellationCause.USER_CANCEL
                    : null);
        }

        public boolean steer() {
            return cause != null && cause.isSteer();
        }
    }
}
